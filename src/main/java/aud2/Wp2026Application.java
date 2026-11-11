package aud2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;

@SpringBootApplication

@ServletComponentScan
public class Wp2026Application {

    public static void main(String[] args) {
        SpringApplication.run(Wp2026Application.class, args);
    }
    

}
