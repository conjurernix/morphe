(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib-modules
  {:core  {:lib 'io.github.nikolaspafitis/morphe.core
           :dir "modules/core"
           :description "Declarative, purely functional Entity Component System for Clojure"}
   :quil  {:lib 'io.github.nikolaspafitis/morphe.quil
           :dir "modules/quil"
           :description "Quil window, input, and 2D rendering adapter for Morphe"}
   :lwjgl {:lib 'io.github.nikolaspafitis/morphe.lwjgl
           :dir "modules/lwjgl"
           :description "LWJGL window, input, 2D, and basic 3D rendering adapter for Morphe"}})

(def default-all-modules
  ["modules/core" "modules/quil" "modules/lwjgl"])

(def local->mvn-internal
  {'morphe/core  'io.github.nikolaspafitis/morphe.core
   'morphe/quil  'io.github.nikolaspafitis/morphe.quil
   'morphe/lwjgl 'io.github.nikolaspafitis/morphe.lwjgl})

(defn compute-version
  "Derives a version from the explicit option or the latest Git tag."
  [opts]
  (or (:version opts)
      (try
        (let [tag (some-> (b/git-process {:git-args ["describe" "--tags" "--abbrev=0"]})
                          str/trim)
              version (some-> tag (str/replace-first #"^v" ""))]
          (when (and (seq version) (re-find #"^\d+\.\d+" version))
            version))
        (catch Exception _ nil))
      "0.1.0-SNAPSHOT"))

(defn version
  "Prints and returns the current derived or overridden version."
  [opts]
  (let [value (compute-version opts)]
    (println value)
    value))

(defn- resolve-lib-modules
  [opts]
  (let [requested (or (:modules opts) (:submodules opts) (keys lib-modules))]
    (mapv (fn [module]
            (let [key (if (keyword? module)
                        module
                        (or (some (fn [[key value]] (when (= (:dir value) module) key)) lib-modules)
                            (keyword (str module))))]
              (or (get lib-modules key)
                  (throw (ex-info (str "Unknown Morphe module: " module)
                                  {:available (keys lib-modules)})))))
          (if (coll? requested) requested [requested]))))

(defn- module-basis
  [dir version]
  (let [basis (binding [b/*project-root* (b/resolve-path dir)]
                (b/create-basis {:project "deps.edn"}))]
    (reduce (fn [current-basis [local-lib published-lib]]
              (if (contains? (:libs current-basis) local-lib)
                (-> current-basis
                    (update :libs dissoc local-lib)
                    (assoc-in [:libs published-lib] {:mvn/version version}))
                current-basis))
            basis
            local->mvn-internal)))

(defn- module-src-dirs
  [dir]
  (let [existing (filter #(-> (io/file dir %) .exists) ["src" "resources"])]
    (mapv #(str dir "/" %) (if (seq existing) existing ["src"]))))

(defn- jar-opts-for-module
  [{:keys [lib dir description]} version opts]
  (let [target (str "target/" dir)
        class-dir (str target "/classes")
        jar-file (format "target/%s-%s.jar" (name lib) version)]
    (merge opts
           {:lib lib
            :version version
            :jar-file jar-file
            :basis (module-basis dir version)
            :class-dir class-dir
            :target target
            :src-dirs (module-src-dirs dir)
            :scm {:url "https://github.com/nikolaspafitis/morphe"
                  :tag (str "v" version)}
            :pom-data [[:description description]
                       [:url "https://github.com/nikolaspafitis/morphe"]
                       [:licenses
                        [:license
                         [:name "Eclipse Public License 1.0"]
                         [:url "https://opensource.org/license/epl-1-0"]
                         [:distribution "repo"]]]] })))

(defn clean
  "Deletes build output."
  [opts]
  (b/delete {:path "target"})
  opts)

(defn check
  "Checks that all declared modules have their expected source and dependency files."
  [opts]
  (doseq [{:keys [dir]} (resolve-lib-modules opts)]
    (doseq [path [dir (str dir "/deps.edn") (str dir "/src")]]
      (when-not (.exists (io/file path))
        (throw (ex-info (str "Missing module path: " path) {:path path})))))
  (println "Module layout is valid.")
  opts)

(defn- run-command!
  [label dir args]
  (println (str "\n=== " label " (" dir ") ==="))
  (let [{:keys [exit]} (b/process {:command-args args :dir dir})]
    (when-not (zero? exit)
      (throw (ex-info (str label " failed") {:dir dir :args args :exit exit}))))
  nil)

(defn test
  "Runs the test suite in each selected library module."
  [opts]
  (doseq [{:keys [dir]} (resolve-lib-modules opts)]
    (run-command! "Testing" dir ["clojure" "-M:test"]))
  opts)

(defn example-test
  "Runs the platformer example tests."
  [opts]
  (run-command! "Testing platformer example" "examples/platformer" ["clojure" "-M:test"])
  opts)

(defn jar
  "Builds and installs selected module JARs in one version."
  [opts]
  (let [version (compute-version opts)]
    (doseq [module (resolve-lib-modules opts)]
      (let [{:keys [class-dir jar-file src-dirs] :as module-opts}
            (jar-opts-for-module module version opts)]
        (println (str "\n=== Building " (:lib module) " " version " ==="))
        (b/delete {:path class-dir})
        (b/write-pom module-opts)
        (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
        (b/jar module-opts)
        (b/install module-opts)
        (println (str "Built " jar-file))))
    (println "\nModule JAR build complete."))
  opts)

(defn install
  "Installs selected module JARs in the local Maven repository."
  [opts]
  (jar opts)
  opts)

(defn deploy
  "Deploys selected module JARs and POMs to Clojars."
  [opts]
  (let [version (compute-version opts)]
    (doseq [module (resolve-lib-modules opts)]
      (let [{:keys [jar-file class-dir] :as module-opts}
            (jar-opts-for-module module version opts)]
        (when-not (.exists (io/file jar-file))
          (throw (ex-info (str "Build the JAR before deploying: " jar-file)
                          {:jar-file jar-file})))
        (dd/deploy {:installer :remote
                    :artifact (b/resolve-path jar-file)
                    :pom-file (b/pom-path {:lib (:lib module-opts)
                                           :class-dir class-dir})})))
    (println "Module deployment complete."))
  opts)

(defn- git-tag!
  [version]
  (let [tag (str "v" version)
        {:keys [exit]} (b/process {:command-args ["git" "tag" "-a" tag "-m" (str "Release " version)]})]
    (when-not (zero? exit)
      (throw (ex-info (str "Unable to create Git tag " tag) {:exit exit})))
    (println (str "Created Git tag " tag))))

(defn- parse-version
  [value]
  (when-let [[_ major minor patch qualifier qualifier-number]
             (re-matches #"^v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(?:\.?([0-9]+))?)?$"
                         (str/trim value))]
    {:major (parse-long major)
     :minor (parse-long minor)
     :patch (parse-long patch)
     :qualifier (some-> qualifier str/lower-case)
     :qualifier-number (some-> qualifier-number parse-long)}))

(defn- format-version
  [{:keys [major minor patch qualifier qualifier-number]}]
  (str major "." minor "." patch
       (when qualifier
         (str "-" qualifier (when qualifier-number qualifier-number)))))

(defn- next-version
  [current bump-type]
(let [{:keys [major minor patch qualifier qualifier-number]}
        (or (parse-version current) {:major 0 :minor 1 :patch 0})]
    (format-version
     (case bump-type
       :major {:major (inc major) :minor 0 :patch 0}
       :minor {:major major :minor (inc minor) :patch 0}
       :patch {:major major :minor minor :patch (inc patch)}
       :alpha (if (= qualifier "alpha")
                {:major major :minor minor :patch patch
                 :qualifier "alpha" :qualifier-number (inc (or qualifier-number 0))}
                {:major major :minor minor :patch (inc patch)
                 :qualifier "alpha" :qualifier-number 1})
       :beta (if (= qualifier "beta")
               {:major major :minor minor :patch patch
                :qualifier "beta" :qualifier-number (inc (or qualifier-number 0))}
               {:major major :minor minor :patch (if qualifier patch (inc patch))
                :qualifier "beta" :qualifier-number 1})
       :rc (if (= qualifier "rc")
             {:major major :minor minor :patch patch
              :qualifier "rc" :qualifier-number (inc (or qualifier-number 0))}
             {:major major :minor minor :patch (if qualifier patch (inc patch))
              :qualifier "rc" :qualifier-number 1})
       :snapshot {:major major :minor minor :patch patch :qualifier "SNAPSHOT"}
       :release {:major major :minor minor :patch patch}
       (throw (ex-info (str "Unsupported version bump: " bump-type)
                       {:type bump-type}))))))

(defn bump
  "Creates a lockstep release tag. Use :dry-run true to preview it."
  [opts]
  (let [current (compute-version {})
        target (or (:to opts) (:version opts)
                   (next-version current (or (:type opts) :patch)))
        dry-run? (:dry-run opts)]
    (println (str current " -> " target))
    (when-not dry-run?
      (git-tag! target))
    (assoc opts :version target :previous-version current)))

(defn bump-major [opts] (bump (assoc opts :type :major)))
(defn bump-minor [opts] (bump (assoc opts :type :minor)))
(defn bump-patch [opts] (bump (assoc opts :type :patch)))
(defn bump-alpha [opts] (bump (assoc opts :type :alpha)))
(defn bump-beta [opts] (bump (assoc opts :type :beta)))
(defn bump-rc [opts] (bump (assoc opts :type :rc)))
(defn bump-release [opts] (bump (assoc opts :type :release)))
(defn bump-snapshot [opts] (bump (assoc opts :type :snapshot)))

(defn ci
  "Runs layout checks, module tests, example tests, and packaging."
  [opts]
  (clean opts)
  (check opts)
  (test opts)
  (example-test opts)
  (jar opts)
  opts)
