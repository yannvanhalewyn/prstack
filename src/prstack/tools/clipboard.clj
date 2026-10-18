(ns prstack.tools.clipboard
  "Copies text to the system clipboard. Uses a native clipboard tool when one
  is available, and falls back to the OSC 52 escape sequence, which asks the
  terminal itself to set the clipboard."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.string :as str])
  (:import
    (java.util Base64)))

(defn- ssh-session? []
  (boolean (or (System/getenv "SSH_TTY") (System/getenv "SSH_CONNECTION"))))

(defn- native-commands
  "Clipboard commands to try for the current platform, in order of preference."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "mac") [["pbcopy"]]
      (str/includes? os "windows") [["clip"]]
      :else
      (cond-> []
        (System/getenv "WAYLAND_DISPLAY") (conj ["wl-copy"])
        (System/getenv "DISPLAY") (conj ["xclip" "-selection" "clipboard"]
                                    ["xsel" "--clipboard" "--input"])
        ;; WSL
        true (conj ["clip.exe"])))))

(defn- copy-native!
  "Pipes `text` into `cmd`. Returns true when it succeeded."
  [cmd text]
  (try
    ;; Output is discarded since xclip keeps running in the background to
    ;; serve the selection, which would otherwise block reading its output.
    (zero? (:exit @(p/process cmd {:in text :out :discard :err :discard})))
    (catch Exception _
      false)))

(defn- copy-osc52!
  "Asks the terminal to set the clipboard. Not every terminal supports this,
  and there is no way to know whether it did."
  [text]
  (let [encoded (.encodeToString (Base64/getEncoder) (.getBytes ^String text "UTF-8"))]
    (print (str "\u001b]52;c;" encoded "\u0007"))
    (flush)))

(defn copy!
  "Copies `text` to the clipboard. Returns `:clipboard/native` when a native
  clipboard tool was used, or `:clipboard/osc52` when it fell back to OSC 52.
  Over SSH, native tools would copy to the remote machine's clipboard, so OSC 52
  is used directly."
  [text]
  (if (and (not (ssh-session?))
           (some #(and (fs/which (first %)) (copy-native! % text))
             (native-commands)))
    :clipboard/native
    (do (copy-osc52! text)
      :clipboard/osc52)))
