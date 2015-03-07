package kanjava;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_objdetect.*;

@SpringBootApplication
@RestController
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
    private static final Logger log = LoggerFactory.getLogger(App.class); // 後で使う

    @Autowired // FaceDetectorをインジェクション
    FaceDetector faceDetector;
    @Autowired
    JmsMessagingTemplate jmsMessagingTemplate; // メッセージ操作用APIのJMSラッパー

    @Bean
        // HTTPのリクエスト・レスポンスボディにBufferedImageを使えるようにする
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }

    // curl -v -F 'file=@hoge.jpg' http://localhost:8080/duker > after.jpg という風に使えるようにする
    @RequestMapping(value = "/duker", method = RequestMethod.POST)
    // POSTで/dukerへのリクエストに対する処理
    BufferedImage duker(@RequestParam Part file /* パラメータ名fileのマルチパートリクエストのパラメータを取得 */) throws IOException {
        Mat source = Mat.createFrom(ImageIO.read(file.getInputStream())); // Part -> BufferedImage -> Matと変換
        faceDetector.detectFaces(source, FaceTranslator::duker); // 対象のMatに対して顔認識。認識結果に対してduker関数を適用する。
        BufferedImage image = source.getBufferedImage(); // Mat -> BufferedImage
        return image;
    }

    @RequestMapping(value = "/send")
    String send(@RequestParam String msg /* リクエストパラメータmsgでメッセージ本文を受け取る */) {
        Message<String> message = MessageBuilder
                .withPayload(msg)
                .build(); // メッセージを作成
        jmsMessagingTemplate.send("hello", message); // 宛先helloにメッセージを送信
        return "OK"; // とりあえずOKと即時応答しておく
    }

    @RequestMapping(value = "/queue", method = RequestMethod.POST)
    String queue(@RequestParam Part file) throws IOException {
        byte[] src = StreamUtils.copyToByteArray(file.getInputStream()); // InputStream -> byte[]
        Message<byte[]> message = MessageBuilder.withPayload(src).build(); // byte[]を持つMessageを作成
        jmsMessagingTemplate.send("faceConverter", message); // convertAndSend("faceConverter", src)でも可
        return "OK";
    }

    @JmsListener(destination = "hello" /* 処理するメッセージの宛先を指定 */, concurrency = "1-5")
    void handleHelloMessage(Message<String> message /* 送信されたメッセージを受け取る */) {
        log.info("received! {}", message);
        log.info("msg={}", message.getPayload());
    }

    @JmsListener(destination = "faceConverter", concurrency = "1-5")
    void convertFace(Message<byte[]> message) throws IOException {
        log.info("received! {}", message);
        try (InputStream stream = new ByteArrayInputStream(message.getPayload())) { // byte[] -> InputStream
            Mat source = Mat.createFrom(ImageIO.read(stream)); // InputStream -> BufferedImage -> Mat
            faceDetector.detectFaces(source, FaceTranslator::duker);
            BufferedImage image = source.getBufferedImage();
            // do nothing...
        }
    }
}

@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class FaceDetector {
    @Value("${classifierFile:classpath:/haarcascade_frontalface_default.xml}")
    File classifierFile;

    CascadeClassifier classifier;

    static final Logger log = LoggerFactory.getLogger(FaceDetector.class);

    public void detectFaces(Mat source, BiConsumer<Mat, Rect> detectAction) {
        // 顔認識結果
        Rect faceDetections = new Rect();
        // 顔認識実行
        classifier.detectMultiScale(source, faceDetections);
        // 認識した顔の数
        int numOfFaces = faceDetections.limit();
        log.info("{} faces are detected!", numOfFaces);
        for (int i = 0; i < numOfFaces; i++) {
            // i番目の認識結果
            Rect r = faceDetections.position(i);
            // 認識結果を変換処理にかける
            detectAction.accept(source, r);
        }
    }

    @PostConstruct
    void init() throws IOException {
        if (log.isInfoEnabled()) {
            log.info("load {}", classifierFile.toPath());
        }
        // 分類器の読み込み
        this.classifier = new CascadeClassifier(classifierFile.toPath()
                .toString());
    }
}

class FaceTranslator {
    public static void duker(Mat source, Rect r) {
        int x = r.x(), y = r.y(), h = r.height(), w = r.width();
        // Dukeのように描画する
        // 上半分の黒四角
        rectangle(source, new Point(x, y), new Point(x + w, y + h / 2),
                new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        // 下半分の白四角
        rectangle(source, new Point(x, y + h / 2), new Point(x + w, y + h),
                new Scalar(255, 255, 255, 0), -1, CV_AA, 0);
        // 中央の赤丸
        circle(source, new Point(x + h / 2, y + h / 2), (w + h) / 12,
                new Scalar(0, 0, 255, 0), -1, CV_AA, 0);
    }
}