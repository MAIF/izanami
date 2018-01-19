package izanami.example.tvdb;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tvshows")
public class TvdbController {

    final TvdbApi tvdbApi;

    @Autowired
    public TvdbController(TvdbApi tvdbApi) {
        this.tvdbApi = tvdbApi;
    }

    @GetMapping("/_search")
    public List<TvdbApi.TvshowResume> searchTvShow(@RequestParam("name")  String name) {
        return tvdbApi.search(name);
    }

    @GetMapping("/{id}")
    public TvdbApi.TvshowResume getTvShow(@PathVariable("id") Long id) {
        return tvdbApi.get(id);
    }

    @GetMapping("/{id}/episodes")
    public List<TvdbApi.EpisodeResume> episodes(@PathVariable("id") Long id) {
        return tvdbApi.episodes(id);
    }

}
