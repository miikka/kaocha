(ns lambdaisland.kaocha.integration-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn invoke-runner [& args]
  (apply shell/sh "clj" "-m" "lambdaisland.kaocha.runner" args))

(defn invoke-with-config [config & args]
  (let [tmpfile (java.io.File/createTempFile "tests" ".edn")]
    (doto tmpfile
      (.deleteOnExit)
      (spit (prn-str config)))
    (apply shell/sh
           "clj" "-m" "lambdaisland.kaocha.runner"
           "--config-file" (str tmpfile)
           args)))

(deftest command-line-runner-test
  (testing "it lets you specifiy the test suite name"
    (is (= {:exit 0
            :out ".\n\n1 test vars, 0 failures.\n"
            :err ""}
           (invoke-runner "--no-color" "--config-file" "fixtures/tests.edn" "a"))))

  (testing "it can print the config"
    (is (= (invoke-with-config {:suites [{:id :aaa
                                          :test-paths ["fixtures/a-tests"]
                                          :ns-patterns [#"^foo$"]}]}
                               "--print-config")
           {:exit 0,
            :out (str "{:suites\n"
                      " [{:ns-patterns [#\"^foo$\"],\n"
                      "   :id :aaa,\n"
                      "   :test-paths [\"fixtures/a-tests\"]}],\n"
                      " :color true,\n"
                      " :reporter lambdaisland.kaocha.report/progress}\n")
            :err ""})))

  (testing "it elegantly reports when no tests are found"
    (is (= (invoke-with-config {:color false
                                :suites [{:id :empty
                                          :test-paths ["fixtures/a-tests"]
                                          :ns-patterns [#"^foo$"]}]})
           {:exit 0, :out "\n\n0 test vars, 0 failures.\n", :err ""}))))