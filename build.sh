#!/bin/bash
lein clean && lein uberjar && lein cljsbuild once min
