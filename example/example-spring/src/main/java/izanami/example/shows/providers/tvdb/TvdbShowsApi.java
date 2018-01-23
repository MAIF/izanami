package izanami.example.shows.providers.tvdb;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Option;
import izanami.example.shows.Shows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

public class TvdbShowsApi implements Shows {

    private final static Logger LOGGER = LoggerFactory.getLogger(TvdbShowsApi.class);

    private final RestTemplate restTemplate;
    private final String url;
    private final String imageBaseUrl;
    private final Login login;

    private final AtomicReference<String> accessToken = new AtomicReference<>();

    public TvdbShowsApi(RestTemplate restTemplate, String url, String imageBaseUrl, Login login) {
        this.restTemplate = restTemplate;
        this.url = url;
        this.imageBaseUrl = imageBaseUrl;
        this.login = login;
    }

    @Override
    public Option<Show> get(String id) {
        return getTvDbShows(id).map(tvshowResume ->
                tvshowResume.toShow(
                        this.imageBaseUrl,
                        listTvDbShowsEpisodes(id)
                                .map(EpisodeResume::toEpisode)
                                .groupBy(e -> e.seasonNumber)
                                .map(t -> new Shows.Season(t._1, t._2)).toList()
                )
        );
    }

    @Override
    public List<ShowResume> search(String serie) {
        List<TvshowResume> tvshowResumes = searchTvDbShows(serie);
        return tvshowResumes
                .map(s -> s.toShowResume(this.imageBaseUrl));
    }

    private String login() {
        ObjectNode jsonNodes = restTemplate.postForObject(url + "/login", login, ObjectNode.class);
        LOGGER.info("Login with {}", login);
        return jsonNodes.get("token").asText();
    }

    private String getAccessToken() {
        String currentToken = accessToken.get();
        if (currentToken == null) {
            String token = login();
            LOGGER.info("Auth token {}", token);
            accessToken.set(token);
            return token;
        } else {
            return currentToken;
        }
    }

    private List<TvshowResume> searchTvDbShows(String serie) {
        if (StringUtils.isEmpty(serie)) {
            return List.empty();
        } else {
            String uri = UriComponentsBuilder.fromHttpUrl(url).path("/search/series").queryParam("name", serie).build().toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + getAccessToken());
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(null, headers);

            try {
                LOGGER.info("Searching show for {}, => {}", serie, uri);
                ResponseEntity<PagedResponse<TvshowResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<PagedResponse<TvshowResume>>() {
                });
                return response.getBody().data;
            } catch (HttpClientErrorException e) {
                return List.empty();
            }
        }
    }


    private Option<TvshowResume> getTvDbShows(String id) {
        String uri = UriComponentsBuilder.fromHttpUrl(url).path("/series/" + id).build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getAccessToken());
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        try {
            LOGGER.info("Get show {}, => {}", id, uri);
            ResponseEntity<SimpleResponse<TvshowResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<SimpleResponse<TvshowResume>>() {});
            return Option.of(response.getBody().data);
        } catch (HttpClientErrorException e) {
            return Option.none();
        }
    }

    private List<EpisodeResume> listTvDbShowsEpisodes(String tvdbId) {
        String uri = UriComponentsBuilder.fromHttpUrl(url).path("/series/" + tvdbId + "/episodes").build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getAccessToken());
        headers.set("Accept", "application/json");
        LOGGER.info("Get tvshow episodes {}, => {}", tvdbId, uri);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        try {
            ResponseEntity<PagedResponse<EpisodeResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<PagedResponse<EpisodeResume>>() {});
            return response.getBody().data;
        } catch (HttpClientErrorException e) {
            return List.empty();
        }
    }


    public static class TvshowResume {
        public String banner;
        public Date firstAired;
        public Long id;
        public String imdbId;
        public String network;
        public String overview;
        public String seriesName;
        public String status;

        @Override
        public String toString() {
            return "ShowResume{" +
                    "image='" + banner + '\'' +
                    ", firstAired=" + firstAired +
                    ", id=" + id +
                    ", network='" + network + '\'' +
                    ", description='" + overview + '\'' +
                    ", title='" + seriesName + '\'' +
                    ", status='" + status + '\'' +
                    '}';
        }


        public Show toShow(String baseUrl, List<Season> seasons) {
            return new Show(
                    String.valueOf(this.id),
                    this.seriesName,
                    this.overview,
                    Option.of(this.banner).filter(i -> !i.isEmpty()).map(i -> baseUrl + i).getOrNull(),
                    seasons
            );
        }

        public ShowResume toShowResume(String baseUrl) {
            return new ShowResume(
                    String.valueOf(this.id),
                    this.seriesName,
                    this.overview,
                    Option.of(this.banner).filter(i -> !i.isEmpty()).map(i -> baseUrl + i).getOrNull(),
                    "tvdb"
            );
        }
    }

    public static class PagedResponse<T> {
        public List<T> data;
    }

    public static class SimpleResponse<T> {
        public T data;
    }

    public static class EpisodeResume {
        public Long id;
        public Long airedEpisodeNumber;
        public Long airedSeason;
        public String episodeName;
        public String overview;

        public Episode toEpisode() {
            return new Episode(
                    String.valueOf(this.id),
                    this.airedEpisodeNumber,
                    this.airedSeason,
                    this.episodeName,
                    this.overview,
                    Boolean.FALSE
            );
        }
    }

    public static class Login {
        public String apikey;

        public Login() {
        }

        public Login(String apiKey) {
            this.apikey = apiKey;
        }

        @Override
        public String toString() {
            return "Login{" +
                    "apikey='" + apikey + '\'' +
                    '}';
        }
    }

}
