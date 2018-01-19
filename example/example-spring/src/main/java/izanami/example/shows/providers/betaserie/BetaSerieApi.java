package izanami.example.shows.providers.betaserie;

import io.vavr.collection.List;
import io.vavr.control.Option;
import izanami.example.shows.Shows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

public class BetaSerieApi implements Shows {

    private final static Logger LOGGER = LoggerFactory.getLogger(BetaSerieApi.class);

    private final String url;
    private final String apikey;
    private final RestTemplate restTemplate;

    public BetaSerieApi(String url, String apikey, RestTemplate restTemplate) {
        this.url = url;
        this.apikey = apikey;
        this.restTemplate = restTemplate;
    }

    @Override
    public List<ShowResume> search(String serie) {
        return searchBetaSerie(serie)
                .flatMap(s -> getBetaSerie(s.thetvdb_id))
                .map(BetaSerie::toShowResume);
    }

    @Override
    public Option<Show> get(String id) {
        return getBetaSerie(id).map(betaSerie ->
                    betaSerie.toShow(
                        betaSerieEpisodes(id)
                            .map(EpisodeResume::toEpisode)
                            .groupBy(e -> e.seasonNumber)
                            .map(t -> new Shows.Season(t._1, t._2))
                            .toList()
                    )
        );
    }

    private List<BetaSerieResume> searchBetaSerie(String text) {
        String uri = UriComponentsBuilder
                .fromHttpUrl(url)
                .path("/search/all")
                .queryParam("query", text)
                .queryParam("key", this.apikey)
                .build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null , headers);
        LOGGER.info("Search show {}, => {}", text, uri);
        try {
            ResponseEntity<BetaSerieShows> response = restTemplate.exchange(uri, HttpMethod.GET, entity, BetaSerieShows.class);
            return response.getBody().shows;
        } catch (HttpClientErrorException e) {
            return List.empty();
        }
    }


    private Option<BetaSerie> getBetaSerie(String id) {
        String uri = UriComponentsBuilder
                .fromHttpUrl(url)
                .path("/shows/display")
                .queryParam("thetvdb_id", id)
                .queryParam("key", this.apikey)
                .build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null , headers);
        LOGGER.info("Get show {}, => {}", id, uri);
        try {
            ResponseEntity<BetaSerieShow> response = restTemplate.exchange(uri, HttpMethod.GET, entity, BetaSerieShow.class);
            return Option.of(response.getBody().show);
        } catch (HttpClientErrorException e) {
            return Option.none();
        }
    }


    private List<EpisodeResume> betaSerieEpisodes(String id) {
        String uri = UriComponentsBuilder
                .fromHttpUrl(url)
                .path("/shows/episodes")
                .queryParam("thetvdb_id", id)
                .queryParam("key", this.apikey)
                .build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null , headers);
        LOGGER.info("Get episodes show {}, => {}", id, uri);
        try {
            ResponseEntity<BetaSerieEpisodes> response = restTemplate.exchange(uri, HttpMethod.GET, entity, BetaSerieEpisodes.class);
            return response.getBody().episodes;
        } catch (HttpClientErrorException e) {
            return List.empty();
        }
    }


    public static class BetaSerieShows {
        public List<BetaSerieResume> shows;
    }
    public static class BetaSerieEpisodes {
        public List<EpisodeResume> episodes;
    }

    public static class BetaSerieShow {
        public BetaSerie show;
    }

    static class BetaSerieResume {
        public String thetvdb_id;
        public String title;

        public BetaSerieResume() {
        }

        public BetaSerieResume(String id, String title) {
            this.thetvdb_id = id;
            this.title = title;
        }
    }

    static class BetaSerie {

        public String thetvdb_id;
        public String title;
        public String description;
        public Images images;

        public BetaSerie() {
        }

        public BetaSerie(String id, String title, String description, Images images) {
            this.thetvdb_id = id;
            this.title = title;
            this.description = description;
            this.images = images;
        }

        public ShowResume toShowResume() {
            return new ShowResume(thetvdb_id, title, description, Option.of(images).map(i -> i.banner).getOrNull());
        }

        public Show toShow(List<Season> seasons) {
            return new Show(thetvdb_id, title, description, Option.of(images).map(i -> i.banner).getOrNull(), seasons);
        }
    }

    public static class EpisodeResume {
        public Long id;
        public Long episode;
        public Long season;
        public String title;
        public String description;

        public Episode toEpisode() {
            return new Episode(
                    String.valueOf(this.id),
                    this.episode,
                    this.season,
                    this.title,
                    this.description,
                    Boolean.FALSE
            );
        }
    }

    public static class Images {
        public String show;
        public String banner;
        public String box;
        public String poster;
    }



}
