package izanami.example.me;

import izanami.javadsl.FeatureClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService meService;
    private final FeatureClient featureClient;

    @Autowired
    public MeController(MeService meService, FeatureClient featureClient) {
        this.meService = meService;
        this.featureClient = featureClient;
    }

    @GetMapping(path = "")
    Me get(@CookieValue(value = "userId") String userId) {
        return meService.get(userId);
    }

    @PostMapping(path = "/{serieId}")
    Me addSerie(@CookieValue(value = "userId") String userId, @PathVariable("serieId") String serieId) {
        return meService.addTvShow(userId, serieId);
    }

    @DeleteMapping(path = "/{serieId}")
    Me removeSerie(@CookieValue(value = "userId") String userId, @PathVariable("serieId") String serieId) {
        return meService.removeTvShow(userId, serieId);
    }

    @PostMapping(path = "/{serieId}/episodes/{episodeId}")
    Me markEpisode(
            @CookieValue(value = "userId") String userId,
            @PathVariable("serieId") String serieId,
            @PathVariable("episodeId") String episodeId,
            @RequestParam("watched") Boolean watched
    ) {
        return meService.markEpisode(userId, serieId, episodeId, watched);
    }

    @PostMapping(path = "/{serieId}/seasons/{seasonNumber}")
    ResponseEntity<Me> markSeason(
            @CookieValue(value = "userId") String userId,
            @PathVariable("serieId") String serieId,
            @PathVariable("seasonNumber") Long seasonNumber,
            @RequestParam("watched") Boolean watched
    ) {
        return
                featureClient.featureOrElse("mytvshows:season:markaswatched",
                        () -> ResponseEntity.ok(meService.markSeason(userId, serieId, seasonNumber, watched)),
                        () -> ResponseEntity.badRequest().<Me>body(null)
                ).get();

    }

}
