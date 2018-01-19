package izanami.example.me;

import io.vavr.collection.List;
import io.vavr.collection.Seq;
import izanami.example.tvdb.TvdbApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class MeService {

    private final static Logger LOGGER = LoggerFactory.getLogger(MeService.class);

    private final TvdbApi tvdbApi;
    private final MeRepository meRepository;

    @Autowired
    public MeService(TvdbApi tvdbApi, MeRepository meRepository) {
        this.tvdbApi = tvdbApi;
        this.meRepository = meRepository;
    }

    public Me addTvShow(String userId, Long tvdbId) {
        Me me = get(userId);

        List<Me.Tvshow> tvshows = List.ofAll(me.tvshows);
        if (!tvshows.exists(tvshow -> tvshow.id.equals(tvdbId))) {
            TvdbApi.TvshowResume tvshowResume = tvdbApi.get(tvdbId);
            java.util.List<TvdbApi.EpisodeResume> episodes = tvdbApi.episodes(tvdbId);

            Seq<Me.Season> seasons = List.ofAll(episodes)
                    .map(Me.Episode::fromResume)
                    .groupBy(e -> e.airedSeason)
                    .map(t -> new Me.Season(t._1, t._2.toJavaList()));
            Me.Tvshow tvshow = Me.Tvshow.fromResume(tvshowResume, seasons.toJavaList());
            LOGGER.info("Adding tvshow for {}: {}", tvdbId, tvshow.seriesName);
            Me updated = me.updateTvShows(tvshows.append(tvshow).toJavaList());
            return meRepository.save(updated);
        } else {
            return me;
        }

    }

    public Me markEpisode(String userId, Long tvdbId, Long episodeId, Boolean watched) {
        Me me = get(userId);

        Me meUpdated = new Me(
            me.userId,
            List.ofAll(me.tvshows)
                .map(tvshow -> {
                    if (tvshow.id.equals(tvdbId)) {
                        return tvshow.updateSeasons(
                            List.ofAll(tvshow.seasons)
                                .map(season ->
                                    new Me.Season(
                                        season.number,
                                        List.ofAll(season.episodes)
                                            .map(episode -> {
                                                if (episode.id.equals(episodeId)) {
                                                    return episode.updateWatched(watched);
                                                } else {
                                                    return episode;
                                                }
                                            })
                                            .toJavaList())
                                )
                                .toJavaList()
                        );
                    } else {
                        return tvshow;
                    }
                })
                .toJavaList()
        );

        meRepository.save(meUpdated);
        return meUpdated;

    }

    public Me markSeason(String userId, Long tvdbId, Long seasonNumber, Boolean watched) {
        Me me = get(userId);

        if (!List.ofAll(me.tvshows).exists(tvshow -> tvshow.id.equals(tvdbId))) {

            Me meUpdated = new Me(
                    me.userId,
                    List.ofAll(me.tvshows)
                            .map(tvshow -> {
                                if (tvshow.id.equals(tvdbId)) {
                                    return tvshow.updateSeasons(
                                            List.ofAll(tvshow.seasons)
                                                    .map(season -> {
                                                        if (season.number.equals(seasonNumber)) {
                                                            return new Me.Season(
                                                                    season.number,
                                                                    List.ofAll(season.episodes)
                                                                            .map(episode ->
                                                                                episode.updateWatched(watched)
                                                                            )
                                                                            .toJavaList()
                                                            );
                                                        } else {
                                                            return season;
                                                        }
                                                    })
                                                    .toJavaList()
                                    );
                                } else {
                                    return tvshow;
                                }
                            })
                            .toJavaList()
            );

            meRepository.save(meUpdated);
            return meUpdated;
        } else {
            return me;
        }
    }

    public Me get(String userId) {
        return meRepository.get(userId).getOrElse(new Me(userId, Collections.emptyList()));
    }

    public Me removeTvShow(String userId, Long serieId) {
        Me me = get(userId);
        Me updated = new Me(me.userId, List.ofAll(me.tvshows).filter(tvshow -> !tvshow.id.equals(serieId)).toJavaList());
        meRepository.save(updated);
        return updated;
    }
}
