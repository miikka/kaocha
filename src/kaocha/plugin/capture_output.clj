(ns kaocha.plugin.capture-output
  (:require [kaocha.plugin :as plugin :refer [defplugin]]
            [kaocha.testable :as testable]
            [kaocha.hierarchy :as hierarchy])
  (:import [java.io OutputStream ByteArrayOutputStream PrintStream PrintWriter]))

;; Many props to eftest for much of this code

(def ^:dynamic *test-buffer* nil)

(def active-buffers (atom #{}))

(defn make-buffer []
  (ByteArrayOutputStream.))

(defn read-buffer [buffer]
  (some-> buffer (.toByteArray) (String.)))

(defmacro with-test-buffer [buffer & body]
  `(try
    (swap! active-buffers conj ~buffer)
    (binding [*test-buffer* ~buffer]
      ~@body)
    (finally
      (swap! active-buffers disj ~buffer))))

(defn- doto-capture-buffer [f]
  (if *test-buffer*
    (f *test-buffer*)
    (doseq [buffer @active-buffers]
      (f buffer))))

(defn- create-proxy-output-stream ^OutputStream []
  (proxy [OutputStream] []
    (write
      ([data]
       (if (instance? Integer data)
         (doto-capture-buffer #(.write % ^int data))
         (doto-capture-buffer #(.write % ^bytes data 0 (alength ^bytes data)))))
      ([data off len]
       (doto-capture-buffer #(.write % data off len))))))

(defn init-capture []
  (let [old-out             System/out
        old-err             System/err
        proxy-output-stream (create-proxy-output-stream)
        new-stream          (PrintStream. proxy-output-stream)
        new-writer          (PrintWriter. proxy-output-stream)]
    (System/setOut new-stream)
    (System/setErr new-stream)
    {:captured-writer new-writer
     :old-system-out  old-out
     :old-system-err  old-err}))

(defn restore-capture [{:keys [old-system-out old-system-err]}]
  (System/setOut old-system-out)
  (System/setErr old-system-err))

(defmacro with-capture [& body]
  `(let [context# (init-capture)
         writer#  (:captured-writer context#)]
     (try
       (binding [*out* writer#, *err* writer#]
         (with-redefs [*out* writer#, *err* writer#]
           ~@body))
       (finally
         (restore-capture context#)))))

(defplugin kaocha.plugin/capture-output
  (cli-options [opts]
    (conj opts [nil "--[no-]capture-output" "Capture output during tests."]))

  (config [config]
    (let [cli-flag (get-in config [:kaocha/cli-options :capture-output])]
      (assoc config ::capture-output? (if (some? cli-flag) cli-flag true))))

  (wrap-run [run test-plan]
    (if (::capture-output? test-plan)
      (fn [& args]
        (with-capture (apply run args)))
      run))

  (pre-test [testable test-plan]
    (if (::capture-output? test-plan)
      (let [buffer (make-buffer)]
        (cond-> testable
          (hierarchy/leaf? testable)
          (-> (assoc ::buffer buffer)
              (update :kaocha.testable/wrap conj (fn [t] #(with-test-buffer buffer (t)))))))
      testable))

  (post-test [testable test-plan]
    (if (and (::capture-output? test-plan) (::buffer testable))
      (-> testable
          (assoc ::output (read-buffer (::buffer testable)))
          (dissoc ::buffer))
      testable)))
