# video-note-taker

This is a cljs app that I made in 2020. It provides a searchable interface for uploading and viewing videos.
It was built for a friend who had a large number of family videos on VHS that they were digitizing.
So I turned it into a product, which I sunset in June 2023 (I chose to pursue software consulting instead of marketing this product, so it never got many users).

Internet archive link: https://web.archive.org/web/20221024064857/https://familymemorystream.com/

If using this code, please consider that the cljs ecosystem has changed substantially since 2020. You may want to consider using shadow-cljs and reframe.

## Development Mode

### cljs-devtools

To enable:

1. Open Chrome's DevTools,`Ctrl-Shift-i`
2. Open "Settings", `F1`
3. Check "Enable custom formatters" under the "Console" section
4. close and re-open DevTools

### Start Cider from Emacs:

Put this in your Emacs config file:

```
(setq cider-cljs-lein-repl "(do (use 'figwheel-sidecar.repl-api) (start-figwheel!) (cljs-repl))")
```

Navigate to a clojurescript file and start a figwheel REPL with `cider-jack-in-clojurescript` or (`C-c M-J`)

### Run application:

```
lein clean
lein figwheel dev
```

Figwheel will automatically push cljs changes to the browser.

Wait a bit, then browse to [http://localhost:3449](http://localhost:3449).

### Run tests:

```
lein clean
lein doo phantom test once
```

The above command assumes that you have [phantomjs](https://www.npmjs.com/package/phantomjs) installed. However, please note that [doo](https://github.com/bensu/doo) can be configured to run cljs.test in many other JS environments (chrome, ie, safari, opera, slimer, node, rhino, or nashorn). 

### Devcards

```
lein clean
lein figwheel devcards
```

Figwheel will automatically push cljs changes to the browser.

Wait a bit, then browse to [http://localhost:3449/cards.html](http://localhost:3449/cards.html).

---

To build a minified version:

```
lein clean
lein cljsbuild once hostedcards
```

Then open *resources/public/cards.html*

## Production Build

```
lein clean
lein cljsbuild once min
```

# Setting up SSL
A full set of instructions can be found at https://www.sorcerers-tower.net/articles/configuring-jetty-for-https-with-letsencrypt

```
cd /etc/letsencrypt/live/your-domain-name
openssl pkcs12 -export -inkey privkey.pem -in fullchain.pem -out jetty.pkcs12 -passout 'pass:p'
keytool -importkeystore -noprompt -srckeystore jetty.pkcs12 -srcstoretype PKCS12 -srcstorepass p -destkeystore keystore -deststorepass storep
cp keystore /the/same/directory/as/this/readme/
```