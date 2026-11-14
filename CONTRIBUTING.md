# Contributing

Thanks for helping with Singularity Loader!

## Building

You need JDK 21 or newer (JDK 25 recommended, it is what Minecraft 26.3 runs on) and Gradle 9.1+.

```sh
gradle build          # compile, run the tests, create the distribution
gradle runServer      # run the loader from source in ./run (downloads the server on first start)
```

## Guidelines

- Keep the public API (`net.singularitymc.api`) small and documented; everything else lives in
  `net.singularitymc.loader` and may change at any time.
- The API must not expose Gson, ASM, Mixin or Minecraft types.
- Every user-facing failure should be a `LaunchException` with a message that says what to do next.
- Add a unit test for new parsing / resolution behaviour.
- Update `CHANGELOG.md`.

By contributing you agree that your work is licensed under the Apache License 2.0.
