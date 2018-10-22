# Perfs

## To inject experiments data

```bash
sbt

> project simulation
> gatling:test
```

To run only one test 

```bash
sbt

> project simulation
> gatling:testOnly experiments.ABTestingSimulation
```