package izanami.example.tvdb;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivecouchbase.json.Json;
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

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class TvdbApi {

    private final static Logger LOGGER = LoggerFactory.getLogger(TvdbApi.class);

    private final RestTemplate restTemplate;
    private final String url;
    private final Login login;

    private final AtomicReference<String> accessToken = new AtomicReference<>();

    public TvdbApi(RestTemplate restTemplate, String url, Login login) {
        this.restTemplate = restTemplate;
        this.url = url;
        this.login = login;
    }

    public String login() {
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

    public List<TvshowResume> search(String serie) {
        if (StringUtils.isEmpty(serie)) {
            return Collections.emptyList();
        } else {
            String uri = UriComponentsBuilder.fromHttpUrl(url).path("/search/series").queryParam("name", serie).build().toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer "+getAccessToken());
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(null , headers);

            try {
                LOGGER.info("Searching tvshow for {}, => {}", serie, uri);
                ResponseEntity<PagedResponse<TvshowResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<PagedResponse<TvshowResume>>() {});
                return response.getBody().data;
            } catch (HttpClientErrorException e) {
                return Collections.emptyList();
            }
        }
    }


    public TvshowResume get(Long id) {
        String uri = UriComponentsBuilder.fromHttpUrl(url).path("/series/"+id).build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer "+getAccessToken());
        headers.set("Accept", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(null , headers);
        LOGGER.info("Get tvshow {}, => {}", id, uri);
        ResponseEntity<SimpleResponse<TvshowResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<SimpleResponse<TvshowResume>>() {});
        return response.getBody().data;
    }

    public List<EpisodeResume> episodes(Long tvdbId) {
        String uri = UriComponentsBuilder.fromHttpUrl(url).path("/series/" + tvdbId + "/episodes").build().toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer "+getAccessToken());
        headers.set("Accept", "application/json");
        LOGGER.info("Get tvshow episodes {}, => {}", tvdbId, uri);
        HttpEntity<String> entity = new HttpEntity<>(null , headers);

        ResponseEntity<PagedResponse<EpisodeResume>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<PagedResponse<EpisodeResume>>() {});
        return response.getBody().data;
    }


    public static class TvshowResume {
        public String banner;
        public Date firstAired;
        public Long id;
        public String network;
        public String overview;
        public String seriesName;
        public String status;

        @Override
        public String toString() {
            return "TvshowResume{" +
                    "banner='" + banner + '\'' +
                    ", firstAired=" + firstAired +
                    ", id=" + id +
                    ", network='" + network + '\'' +
                    ", overview='" + overview + '\'' +
                    ", seriesName='" + seriesName + '\'' +
                    ", status='" + status + '\'' +
                    '}';
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
        public Long absoluteNumber;
        public Long airedEpisodeNumber;
        public Long airedSeason;
        public Long airedSeasonID;
        public Long dvdEpisodeNumber;
        public Long dvdSeason;
        public String episodeName;
        public String overview;
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
