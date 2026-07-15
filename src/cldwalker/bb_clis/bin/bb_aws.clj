(ns cldwalker.bb-clis.bin.bb-aws
  "Run aws commands the same way the awscli does e.g.
  - bb-aws s3api list-buckets
  - bb-aws s3api get-bucket-acl --bucket X
  Note: Does not support commands with non-option arguments e.g.
    bb-aws lambda invoke --function-name FN OUT_FILE
  Drop into a repl with command's result stored in #'result
  - bb-aws --repl s3api list-buckets"
  {:clj-kondo/config
   '{:linters {:inline-def {:level :off}}}}
  (:require [babashka.cli :as cli]
            [babashka.pods :as pods]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.main :as main]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(pods/load-pod 'org.babashka/aws "0.0.6")
(require '[pod.babashka.aws :as aws])

(defn- clojure-case->camel-case
  [s]
  (->> (str/split s #"-")
       (map str/capitalize)
       str/join))

(def ^:private aws-command->aws-api-api
  {:s3api :s3})

(defn- parse-options-and-args [all-args]
  (loop [options {}
         args all-args]
    (if (some-> (first args) (str/starts-with? "--"))
      (recur (assoc options
                    (str/replace-first (first args) #"^--" "") (second args))
        (drop 2 args))
      [options args])))

(defn- invoke-aws-operation [[cmd subcmd & args] {:keys [region debug]}]
  (let [api (aws-command->aws-api-api (keyword cmd) (keyword cmd))
        op (keyword (clojure-case->camel-case subcmd))
        client (aws/client {:api api :region region})
        [options _] (parse-options-and-args args)
        request (update-keys options
                             #(-> %
                                  clojure-case->camel-case
                                  (str/replace-first #"[A-Z]" str/lower-case)
                                  keyword))]
    (when debug
      (println "API:" api)
      (println "OP:" op)
      (println "REQUEST:" request))
    (try (aws/invoke client {:op op :request request})
      ;; Noisy failure if the wrong number of args given
      ;; See https://github.com/babashka/pod-babashka-aws/blob/d7c4306bdd74ecde16ee3766825b71facb325588/src/pod/babashka/aws.clj#L120-L123 for noise.
      ;; TODO: Actually catch the noisy failure
      (catch Throwable e
        (println "\nError: Unexpected failure occurred. See error message above.")
        (when debug (println "Exception:" e))))))

(defn- auto-result [result subcmd]
  (cond
    ;; e.g. aws ecr describe-repositores
    (and (map? result) (= 1 (count result)))
    (-> result vals first)
    ;; e.g. aws s3 list-buckets
    (let [kw (some-> (str/split subcmd #"-")
                     last
                     str/capitalize
                     keyword)]
      (contains? result kw))
    (result (some-> (str/split subcmd #"-")
                    last
                    str/capitalize
                    keyword))
    :else
    result))

(def ^:private spec
  {:debug {:alias :d :coerce :boolean :desc "Print debug info"}
   :repl {:alias :R :coerce :boolean :desc "Drop into repl with operation result set to #'result"}
   :auto-result {:alias :a :coerce :boolean :desc "Drills into result one level if main entity to list is detected"}
   :region {:alias :r
            :default (or (System/getenv "AWS_REGION") "us-east-2")
            :default-desc "$AWS_REGION or us-east-2"
            :desc "AWS region"}})

(defn- print-help []
  (println (str "Usage: bb-aws [options] <command> <subcommand> [subcommand-options]\n\n"
                "Options:\n"
                (cli/format-opts {:spec (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"})}))))

(defn- command
  "Ignores dispatch's parsed input in favor of raw args so unrecognized
  options pass through to the aws operation"
  [args]
  (if (empty? args)
    (print-help)
    (let [[our-args aws-args] (cli-util/split-leading-opts spec args)
          opts (:opts (cli/parse-args our-args {:spec spec}))
          result (invoke-aws-operation aws-args opts)
          result_ (if (:auto-result opts)
                    (auto-result result (second aws-args))
                    result)]
      (pprint/pprint result_)
      (when (:repl opts)
        (def result result_)
        (main/repl)))))

(defn -main [& args]
  ;; Dispatch is only used for -h and to provide org.babashka.cli/completions
  (cli/dispatch [{:cmds [] :spec spec :fn (fn [_] (command args))}]
                args
                {:prog "bb-aws" :help true :help-fn (fn [_] (print-help))}))

(comment
 (def ^:private s3-client (aws/client {:api :s3 :region "us-east-1"}))
 (aws/doc s3-client :ListBuckets)
 (aws/invoke s3-client {:op :ListBuckets})
 )
