package izanami.example.me;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import io.vavr.control.Option;
import izanami.example.shows.Shows;
import izanami.example.shows.providers.tvdb.TvdbShowsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class MeService {

    private final static Logger LOGGER = LoggerFactory.getLogger(MeService.class);

    private final Shows shows;
    private final MeRepository meRepository;

    @Autowired
    public MeService(Shows shows, MeRepository meRepository) {
        this.shows = shows;
        this.meRepository = meRepository;
    }

    public Me addTvShow(String userId, String id) {
        Me me = get(userId);

        List<Shows.Show> tvshows = List.ofAll(me.shows);
        if (!tvshows.exists(tvshow -> tvshow.id.equals(id))) {
            Option<Shows.Show> mayBeShow = shows.get(id);
            return mayBeShow.map(show -> {
                LOGGER.info("Adding show for {}: {}", id, show.title);
                Me updated = me.updateTvShows(tvshows.append(show));
                return meRepository.save(updated);
            }).getOrElse(me);
        } else {
            return me;
        }

    }

    public Me markEpisode(String userId, String tvdbId, String episodeId, Boolean watched) {
        Me me = get(userId);

        Me meUpdated = new Me(
            me.userId,
            me.shows
                .map(tvshow -> {
                    if (tvshow.id.equals(tvdbId)) {
                        return tvshow.updateSeasons(
                            tvshow.seasons
                                .map(season ->
                                    new Shows.Season(
                                        season.number,
                                        season.episodes
                                            .map(episode -> {
                                                if (episode.id.equals(episodeId)) {
                                                    return episode.updateWatched(watched);
                                                } else {
                                                    return episode;
                                                }
                                            })
                                    )
                                )
                        );
                    } else {
                        return tvshow;
                    }
                })
        );

        meRepository.save(meUpdated);
        return meUpdated;

    }

    public Me markSeason(String userId, String tvdbId, Long seasonNumber, Boolean watched) {
        Me me = get(userId);
        Me meUpdated = new Me(
            me.userId,
            me.shows
                .map(tvshow -> {
                    if (tvshow.id.equals(tvdbId)) {
                        return tvshow.updateSeasons(
                            tvshow.seasons
                                .map(season -> {
                                    if (season.number.equals(seasonNumber)) {
                                        return new Shows.Season(
                                                season.number,
                                                season.episodes
                                                    .map(episode ->
                                                        episode.updateWatched(watched)
                                                    )
                                        );
                                    } else {
                                        return season;
                                    }
                                })
                        );
                    } else {
                        return tvshow;
                    }
                })
        );

        meRepository.save(meUpdated);
        return meUpdated;
    }

    public Me get(String userId) {
        return meRepository.get(userId).getOrElse(new Me(userId, List.empty()));
    }

    public Me removeTvShow(String userId, String serieId) {
        Me me = get(userId);
        Me updated = new Me(me.userId, me.shows.filter(tvshow -> !tvshow.id.equals(serieId)));
        meRepository.save(updated);
        return updated;
    }
}
