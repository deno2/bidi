;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(ns bidi.bidi
  (:import
   (clojure.lang PersistentVector Symbol Keyword PersistentArrayMap Fn)))

;; A PatternSegment is part of a segmented pattern, where the pattern is
;; given as a vector. Each segment can be of a different type, and each
;; segment can optionally be associated with a key, thereby contributing
;; a route parameter.

(defprotocol PatternSegment
  (match-segment [_])
  (param-key [_])
  (unmatch-segment [_ params])
  (matches? [_ s]))


(extend-protocol PatternSegment
  String
  (match-segment [this] (format "\\Q%s\\E" this))
  (unmatch-segment [this _] this)
  (param-key [_] nil)

  java.util.regex.Pattern
  (match-segment [this] (.pattern this))
  (param-key [_] nil)
  (matches? [this s] (re-matches this s))

  Keyword
  ;; By default, a keyword can represent any string.
  (match-segment [_] "(.*)")
  (unmatch-segment [this params]
    (if-let [v (this params)]
      (str v)
      (throw (ex-info (format "No parameter value given for %s" this) {}))))
  (param-key [this] this)

  PersistentVector
  ;; A vector allows a keyword to be associated with a segment. The
  ;; segment comes first, then the keyword.
  (match-segment [this] (format "(%s)" (match-segment (first this))))
  (unmatch-segment [this params]
    (let [k (second this)]
      (if-not (keyword? k)
        (throw (ex-info (format "If a PatternSegment is represented by a vector, the second element must be the key associated with the pattern: %s" this) {})))
      (if-let [v (get params k)]
        (if (matches? (first this) v)
          v
          (throw (ex-info (format "Parameter value of %s (key %s) is not compatible with the route pattern %s" v k this) {})))
        (throw (ex-info (format "No parameter found in params for key %s" k) {})))))
  (param-key [this] (let [k (second this)]
                      (if (keyword? k)
                        k
                        (throw (ex-info (format "If a PatternSegment is represented by a vector, the second element must be the key associated with the pattern: %s" this) {}))))))

;; A Route is a pair. The pair has two halves: a pattern on the left,
;; something that is matched by the pattern on the right.

(defprotocol Pattern
  ;; Return truthy if the given pattern matches the given path. By
  ;; truthy, we mean a map containing (at least) the rest of the path to
  ;; match in a :remainder entry
  (match-pattern [_ ^String path])
  (unmatch-pattern [_ m]))

(defprotocol Matched
  (resolve-handler [_ path])
  (unresolve-handler [_ m]))

(defn match-pair [[pattern matched] m]
    (when-let [pattern-map (match-pattern pattern m)]
      (resolve-handler matched (merge m pattern-map))))

(defn match-beginning
  "Match the beginning of the :remainder value in m. If matched, update
  the :remainder value in m with the path that remains after matching."
  [regex-pattern m]
  (when-let [path (last (re-matches (re-pattern (str regex-pattern "(.*)"))
                                    (:remainder m)))]
      (merge m {:remainder path})))

(defn succeed [handler m]
  (when (= (:remainder m) "")
      (merge (dissoc m :remainder) {:handler handler})))

(extend-protocol Pattern
  String
  (match-pattern [this m] (match-beginning (match-segment this) m))
  (unmatch-pattern [this _] this)

  java.util.regex.Pattern
  (match-pattern [this m] (match-beginning (.pattern this) m))

  Boolean
  (match-pattern [this m] (when this m))

  PersistentVector
  (match-pattern [this m]
    (let [pattern (re-pattern (str (reduce str (map match-segment this)) "(.*)"))]
      (when-let [groups (next (re-matches pattern (:remainder m)))]
        (-> m
            (assoc-in [:remainder] (last groups))
            (update-in [:params] merge (zipmap (keep param-key this) (butlast groups)))))))
  (unmatch-pattern [this m]
    (apply str (map #(unmatch-segment % (:params m)) this)))

  Keyword
  (match-pattern [this m] (when (= this (:request-method m)) m))
  (unmatch-pattern [_ _] "")

  PersistentArrayMap
  (match-pattern [this m]
    (when (every? (fn [[k v]]
                    (cond
                     (or (fn? v) (set? v)) (v (get m k))
                     :otherwise (= v (get m k))))
                  (seq this))
      m))
  (unmatch-pattern [_ _] ""))

(defn unmatch-pair [v m]
  (when-let [r (unresolve-handler (second v) m)]
    (str (unmatch-pattern (first v) m) r)))

(extend-protocol Matched
  String
  (unresolve-handler [_ _] nil)

  PersistentVector
  (resolve-handler [this m] (first (keep #(match-pair % m) this)))
  (unresolve-handler [this m] (first (keep #(unmatch-pair % m) this)))

  Symbol
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  Keyword
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) ""))

  Fn
  (resolve-handler [this m] (succeed this m))
  (unresolve-handler [this m] nil))

(defn match-route
  "Given a route definition data structure and a path, return the
  handler, if any, that matches the path."
  [path route & {:as options}]
  (match-pair route (merge options {:remainder path})))

(defn path-for
  "Given a route definition data structure and an option map, return a
  path that would route to the handler entry in the map. The map must
  also contain the values to any parameters required to create the path."
  [handler routes & {:as params}]
  (unmatch-pair routes {:handler handler :params params}))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter."
  [routes]
  (fn [{:keys [uri] :as request}]
    (let [{:keys [handler params]} (apply match-route uri routes (apply concat (seq request)))]
      (when handler
        (handler (-> request
                     (assoc :route-params params)
                     (update-in [:params] #(merge params %))))))))
