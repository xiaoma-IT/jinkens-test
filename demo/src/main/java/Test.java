import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 排除R2dbc自动配置，解决默认包扫描报错
@SpringBootApplication(exclude = R2dbcAutoConfiguration.class)
@RestController
public class Test {
    public static void main(String[] args) {
        SpringApplication.run(Test.class, args);
        System.out.println("应用启动完成，Jenkins部署成功");
    }

    @GetMapping("/")
    public String hello() {
        return "部署成功，网页正常访问";
    }
}
