(ns census.statsAPI.core
  (:require
    [cljs.core.async    :refer [>! <! chan promise-chan close! take! to-chan
                                pipeline-async timeout]
                        :refer-macros [go alt!]]
    [cuerdas.core       :refer [join numeric? parse-number]]
    [net.cgrand.xforms  :as x]
    ;[census.wmsAPI.core :refer [Icb<-wms-args<<=IO=]]
    [census.utils.core  :refer [$GET$ I-<I= =O?>-cb xf!<< educt<< xf<<
                                amap-type vec-type throw-err map-idcs-range
                                keys->strs ->args strs->keys
                                URL-WMS URL-STATS]]))

(defn kv-pair->str [[k v] separator]
  (join separator [(name k) (str v)]))

(defn stats-url-builder
  "Composes a URL to call Census' statistics API"
  [{:keys [vintage sourcePath geoHierarchy values predicates statsKey]}]
  (str URL-STATS
       (str vintage)
       (join (map #(str "/" %) sourcePath))
       "?get="
       (if (some? values)
           (join "," values)
           "")
       (if (some? predicates)
           (str "&" (str (join "&" (map #(kv-pair->str % "=") predicates))))
           "")
       (keys->strs
         (if (= 1 (count geoHierarchy))
             (str "&for=" (kv-pair->str (first geoHierarchy) ":"))
             (str "&in="  (join "%20" (map #(kv-pair->str % ":") (butlast geoHierarchy)))
                  "&for=" (kv-pair->str (last geoHierarchy) ":"))))
       (if (not (nil? statsKey))
           (str "&key=" statsKey))))


(defn parse-if-number
  "
  Conditionally translates a string into an integer or float if so coercible.
  If not, returns the original string.
  "
  [s]
  (if (numeric? s)
      (parse-number s)
      s))

(defn xf!-csv->clj
  "
  Stateful transducer, which stores the first item as a list of a keys to apply
  (via `zipmap`) to the rest of the items in a collection. Serves to turn the
  Census API response into a more conventional JSON format.

  If provided `:keywords` as an argument, will return a map with Clojure keys.
  Otherwise, will return map keys as strings.
  "
  [{:keys [values predicates]}]
  (let [parse-range [0 (+ (count values) (count predicates))]]
    (xf!<<
      (fn [state rf acc this]
        (let [prev @state]
          (if (nil? prev)
              (do (vreset! state (mapv strs->keys this))
                  nil)
              (rf acc
                  (zipmap (mapv keyword @state)
                          (map-idcs-range parse-if-number
                                          parse-range
                                          this)))))))))

(defn xf-stats->js
  [args]
  (comp
    (xf!-csv->clj args)
    (map #(clj->js % :keywordize-keys true))))


(def $GET$-census-stats ($GET$ :json "Unsuccessful Census stats request... "))

(defn IOE->census-stats
  "
  Internal function for calling the Census API using a Clojure Map. Returns stats
  from Census API unaltered.
  "
  [=I= =O= =E=]
  (go (let [args  (<! =I=)
            url   (stats-url-builder args)]
        ($GET$-census-stats (to-chan [url]) =O= =E=))))

(defn censusStatsJSON
  "
  Solo function to just get Census stats back as conventional JSON instead of
  csv-like output of 'raw' Census API. Not to be coordinated with other functions.

  Note on channels: (cb-<O?=) closes =O= and =E= on completing the callback
  "
  [I cb]
  (let [args (->args I)
        =O= (chan 1 (comp (educt<< (xf-stats->js args))
                          (map to-array)
                          (map js/JSON.stringify)))
        =E= (chan 1 (map throw-err))]
    (go (=O?>-cb IOE->census-stats cb (to-chan [args]) =O= =E=))))


;      e            888                       d8
;     d8b      e88~\888   /~~~8e  888-~88e  _d88__  e88~~8e  888-~\  d88~\
;    /Y88b    d888  888       88b 888  888b  888   d888  88b 888    C888
;   /  Y88b   8888  888  e88~-888 888  8888  888   8888__888 888     Y88b
;  /____Y88b  Y888  888 C888  888 888  888P  888   Y888    , 888      888D
; /      Y88b  "88_/888  "88_-888 888-_88"   '88_/  "88___/  888    \_88P
;                                 888


(defn xf-geoid+<-stat
  "
  Takes an integer argument denoting the number of stat vars the user requested.
  Returns a function of one item (from the Census API response
  collection) to a new map with a hierarchy that will enable deep-merging of
  the stats with a GeoJSON `feature`s `:properties` map.
  "
  [vars#]
  (xf<< (fn [rf acc this]
          (rf acc {(apply str (vals (take-last (- (count this) vars#) this)))
                   {:properties this}}))))

;; Examples ==============================

(defn xf-stats-mergeable
  [args vars#]
  (comp
    (xf!-csv->clj args)
    (xf-geoid+<-stat vars#)))



(defn -<IO-pp-census-stats>-
  [=I= =O= =E=]
  (go (let [args    (<! =I=)
            vars#   (+ (count (get args :values))
                       (count (get args :predicates)))
                         ; TODO ↓ test performance of educt<< vs other means
            =stats+= (chan 1 (educt<< (xf-stats-mergeable args vars#)))]
        (IOE->census-stats (to-chan [args]) =stats+= =E=)
        (>! =O= (<! =stats+=))
        (close! =stats+=))))

#_(let [args {:vintage      "2016"
              :sourcePath   ["acs" "acs5"]
              :geoHierarchy {:county "*"}
              :values       ["B01001_001E" "B01001_001M"]}
        =I= (chan 1)
        =O= (chan 1)
        =E= (chan 1)]
    (go
        (>! =I= args)
        (-<IO-pp-census-stats>- =I= =O= =E=)
        (alt! =O= ([O] (prn O))
              =E= ([E] (prn (str "Error: " E))))
        (close! =I=)
        (close! =O=)
        (close! =E=)))

;; Examples ==============================

#_(let [=I= (chan 1)
        =O= (chan 1)
        args {:vintage      "2016"
              :sourcePath   ["acs" "acs5"]
              :geoHierarchy {:state "44" :school-district-_secondary_ "*"}
              :values       ["B01001_001E" "NAME"]}]
    (go (>! =I= args)
        ;(IO-pp->census-stats =I= =O=)
        (-<IO-pp-census-stats>- =I= =O=)
        (cljs.pprint/pprint (<! =O=))
        (close! =I=)
        (close! =O=)))

; =>
; ({"4400420" {:properties {:B01001_001E 14611, :NAME "Foster-Glocester Regional School District, Rhode Island", :state "44", :school-district-_secondary_ "00420"}}} {"4499999" {:properties {:B01001_001E 1039880, :NAME "Remainder of Rhode Island, Rhode Island", :state "44", :school-district-_secondary_ "99999"}}})

;; =======================================
