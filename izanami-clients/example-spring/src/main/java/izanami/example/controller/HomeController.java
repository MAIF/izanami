package izanami.example.controller;


import io.vavr.collection.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class HomeController {


    private final Environment environment;

    @Autowired
    public HomeController(Environment environment) {
        this.environment = environment;
    }

    @RequestMapping("/")
    public String home(Model model) {
        String profile = List.of(this.environment.getActiveProfiles()).find(p -> p.equals("dev")).getOrElse("prod");
        model.addAttribute("mode", profile);
        return "index";
    }


    @RequestMapping("/login")
    public String loginPage(Model model) {
        return home(model);
    }

    @RequestMapping("/todos/**")
    public String other(Model model) {
        return home(model);
    }


}
