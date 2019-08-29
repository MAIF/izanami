# Izanami


@@@ index

 * [Quick start](quickstart.md)
 * [About](about.md)
 * [Philosophy](philosophy.md)
 * [Features](features.md)
 * [Architecture](architecture/index.md)
 * [Installation](getizanami/index.md)
 * [Settings](settings/index.md)
 * [UI](ui.md)
 * [Api](api.md)
 * [Configs](configs/index.md)
 * [Features](features/index.md)
 * [Experiments](experiments/index.md)
 * [Keys](keys.md)
 * [Events](events.md)
 * [Metrics](metrics.md)
 * [Clients](clients/index.md)
 * [Tutorials](tutorials/index.md)
 * [Performances](performances.md)
 * [Developers](developers/index.md)

@@@

Izanami is a shared configuration, feature flipping and A/B testing service written in scala and developed by the <a href="https://maif.github.io/" target="_blank">MAIF OSS</a> team.

Izanami is perfectly well suited for micro services environments, it provides a UI to allow non-tech users to toggle features and to handle A/B testing.

You can also interact with Izanami using REST APIs from you favorite language or listen to events by registering webhook or using server sent events.

Izanami also provides first class integration : Java, Scala, Node and React and CLI clients are available to integrate Izanami with your application.

@@@ div { .izanami-logo }

![izanami](img/izanami.gif)   

@@@


Download it :


```bash
wget --quiet 'https://dl.bintray.com/maif/binaries/izanami.jar/latest/izanami.jar'
```

Run it (JDK 8 needed)

```zsh
java -jar izanami.jar
```

Go to http://localhost:9000

And then log in using `admin` / `admin123`

Follow the @ref[getting started page](quickstart.md) to see more about Izanami.  
