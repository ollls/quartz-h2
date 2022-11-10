 how to run examples:
 sbt publishLocal
 sbt IO/run
 sbt RIO/run


test: h2load -t1 -D10 -c4 -m10 https://localhost:8443/health
