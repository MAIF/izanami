package izanami.example.me;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService meService;

    @Autowired
    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping(path = "")
    Me get(@CookieValue(value = "userId") String userId) {
        return meService.get(userId);
    }

    @PostMapping(path = "/{serieId}")
    Me addSerie(@CookieValue(value = "userId") String userId, @PathVariable("serieId") Long serieId) {
        return meService.addTvShow(userId, serieId);
    }

    @DeleteMapping(path = "/{serieId}")
    Me removeSerie(@CookieValue(value = "userId") String userId, @PathVariable("serieId") Long serieId) {
        return meService.removeTvShow(userId, serieId);
    }

    @PostMapping(path = "/{serieId}/episodes/{episodeId}")
    Me markEpisode(
            @CookieValue(value = "userId") String userId,
            @PathVariable("serieId") Long serieId,
            @PathVariable("episodeId") Long episodeId,
            @RequestParam("watched") Boolean watched
    ) {
        return meService.markEpisode(userId, serieId, episodeId, watched);
    }

    @PostMapping(path = "/{serieId}/seasons/{seasonNumber}")
    Me markSeason(
            @CookieValue(value = "userId") String userId,
            @PathVariable("serieId") Long serieId,
            @PathVariable("seasonNumber") Long seasonNumber,
            @RequestParam("watched") Boolean watched
    ) {
        return meService.markSeason(userId, serieId, seasonNumber, watched);
    }

}
