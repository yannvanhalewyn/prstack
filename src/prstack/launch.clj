(ns prstack.launch
  "The entry point. Checks the Babashka version before loading prstack, as
  older versions fail on the JLine classes and methods bb-tty uses. Requires
  nothing, so it loads on any version.")

(def ^:private min-bb-version
  "The first release with `TerminalBuilder.graphemeCluster`, which bb-tty calls
  when opening the terminal. 1.12.215 added JLine, but without that method."
  "1.12.218")

(defn- parse-version
  "Parses \"1.12.218\" or \"1.12.218-SNAPSHOT\" into [1 12 218]."
  [s]
  (mapv parse-long (take 3 (re-seq #"\d+" s))))

(defn- supported-bb?
  "True on a recent enough Babashka, or when not on Babashka (a JVM REPL)."
  [bb-version]
  (or (nil? bb-version)
      (not (neg? (compare (parse-version bb-version)
                   (parse-version min-bb-version))))))

(comment
  (supported-bb? "1.12.217")
  (supported-bb? "1.12.218-SNAPSHOT")
  (supported-bb? "1.13.220"))

(defn -main [& args]
  (let [bb-version (System/getProperty "babashka.version")]
    (if (supported-bb? bb-version)
      (apply (requiring-resolve 'prstack.main/-main) args)
      (binding [*out* *err*]
        (println (str "Error: prstack needs Babashka " min-bb-version
                      " or newer, this is " bb-version "."))
        (println "Upgrade with `brew upgrade babashka` or see"
          "https://github.com/babashka/babashka#installation")
        (System/exit 1)))))
