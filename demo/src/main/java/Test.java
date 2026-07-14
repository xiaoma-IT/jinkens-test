import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication(exclude = R2dbcAutoConfiguration.class)
@RestController
public class Test {
    public static void main(String[] args) {
        SpringApplication.run(Test.class, args);
        System.out.println("Jenkins Maven Test Success");
    }

    @GetMapping("/")
    public String showMsg() {
        return "部署成功，版本v20";
    }
}
