# Running Benches

```
sbt
project bench
jmh:run -i 3 -wi 3 -f1 -t15 // iterations 3, warmup iterations 3, forks 1, threads 15
```