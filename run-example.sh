#!/bin/bash

java -jar target/java-trail-1.6.0-cli.jar report examples/sample-trace.json \
  --format html \
  --metadata examples/report-metadata.json \
  --output examples/sample-report.html