package izanami.example.shows;


import io.vavr.collection.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/shows")
public class ShowsController {

    final Shows shows;

    @Autowired
    public ShowsController(Shows shows) {
        this.shows = shows;
    }

    @GetMapping("/_search")
    public List<Shows.ShowResume> searchTvShow(@RequestParam("name")  String name) {
        return shows.search(name);
    }

}
