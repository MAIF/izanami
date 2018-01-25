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
                .flatMap(s -> getBetaSerie(s.id))
                .map(BetaSerie::toShowResume);
    }

    @Override
    public Option<Show> get(String id) {
        return getBetaSerie(id).map(betaSerie ->
                    betaSerie.toShow(
                        betaSerieEpisodes(betaSerie.id)
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
            return List.ofAll(response.getBody().shows);
        } catch (HttpClientErrorException e) {
            return List.empty();
        }
    }


    private Option<BetaSerie> getBetaSerie(String id) {
        String uri = UriComponentsBuilder
                .fromHttpUrl(url)
                .path("/shows/display")
                .queryParam("id", id)
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
                .queryParam("id", id)
                .queryParam("key", this.apikey)
                .build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null , headers);
        LOGGER.info("Get episodes show {}, => {}", id, uri);
        try {
            ResponseEntity<BetaSerieEpisodes> response = restTemplate.exchange(uri, HttpMethod.GET, entity, BetaSerieEpisodes.class);
            return List.ofAll(response.getBody().episodes);
        } catch (HttpClientErrorException e) {
            return List.empty();
        }
    }


    public static class BetaSerieShows {
        public java.util.List<BetaSerieResume> shows;
    }
    public static class BetaSerieEpisodes {
        public java.util.List<EpisodeResume> episodes;
    }

    public static class BetaSerieShow {
        public BetaSerie show;
    }

    static class BetaSerieResume {
        public String id;
        public String thetvdb_id;
        public String imdb_id;
        public String title;

        public BetaSerieResume() {
        }

        public BetaSerieResume(String id, String thetvdb_id, String imdb_id, String title) {
            this.id = id;
            this.thetvdb_id = thetvdb_id;
            this.imdb_id = imdb_id;
            this.title = title;
        }
    }

    static class BetaSerie {
        public String id;
        public String thetvdb_id;
        public String imdb_id;
        public String title;
        public String description;
        public Images images;

        public BetaSerie() {
        }

        public BetaSerie(String id, String imdb_id, String thetvdb_id, String title, String description, Images images) {
            this.id = id;
            this.imdb_id = imdb_id;
            this.thetvdb_id = thetvdb_id;
            this.title = title;
            this.description = description;
            this.images = images;
        }

        public ShowResume toShowResume() {
            return new ShowResume(id, title, description, Option.of(images).map(i -> i.banner).getOrNull(), "betaserie");
        }

        public Show toShow(List<Season> seasons) {
            return new Show(id, title, description, Option.of(images).map(i -> i.banner).getOrNull(), seasons);
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
