# Running Benches

```
sbt
project bench
jmh:run -i 3 -wi 3 -f1 -t15 // iterations 3, warmup iterations 3, forks 1, threads 15
```


## Numbers presently


```
[info] Benchmark                                        Mode  Cnt      Score     Error  Units
[info] LookUpBench.contentionCaffeine                  thrpt   10  68602.693 ± 183.322  ops/s
[info] LookUpBench.contentionConcurrentHashMap         thrpt   10  26815.305 ±  47.615  ops/s
[info] LookUpBench.contentionSingleImmutableMap        thrpt   10  21853.931 ±  82.138  ops/s
[info] LookUpBench.contentionReadsCaffeine             thrpt   10  88898.190 ± 676.454  ops/s
[info] LookUpBench.contentionReadsConcurrentHashMap    thrpt   10  28990.070 ± 161.409  ops/s
[info] LookUpBench.contentionReadsSingleImmutableMap   thrpt   10  24290.804 ± 233.290  ops/s
[info] LookUpBench.contentionWritesCaffeine            thrpt   10  74592.814 ± 811.518  ops/s
[info] LookUpBench.contentionWritesConcurrentHashMap   thrpt   10  40196.853 ± 247.774  ops/s
[info] LookUpBench.contentionWritesSingleImmutableMap  thrpt   10  28423.209 ± 215.411  ops/s
```