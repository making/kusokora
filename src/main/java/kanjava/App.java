package kanjava;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.BiConsumer;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_objdetect.*;

@SpringBootApplication
@RestController
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Autowired // FaceDetectorをインジェクション
    FaceDetector faceDetector;

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
}

@Component
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