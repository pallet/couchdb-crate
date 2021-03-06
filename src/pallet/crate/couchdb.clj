(ns pallet.crate.couchdb
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.service :as service]
   [pallet.argument :as argument]
   [pallet.session :as session]
   [clojure.data.json :as json])
  (:use
   pallet.thread-expr))


(def install-dirs
  {:aptitude ["/var/log/couchdb" "/var/run/couchdb" "/var/lib/couchdb"
              "/etc/couchdb"]
   :yum ["/usr/local/var/log/couchdb" "/usr/local/var/run/couchdb"
         "/usr/local/var/lib/couchdb" "/usr/local/etc/couchdb"]})

(defn install
  "Installs couchdb, leaving all default configuration as-is.  Use of the
   `couchdb function is preferred, as it contains a workaround that makes
   it install cleanly in ubuntu."
  [session]
  ;; this fixes this problem
  ;; https://bugs.launchpad.net/ubuntu/karmic/+source/couchdb/+bug/448682
  ;; apparently impacts centos, too.  The fix implemented here pulled from
  ;; http://www.mail-archive.com/user@couchdb.apache.org/msg05488.html
  ;; I added the /var/run/couchdb dirs, which seemed to be tripping up service
  ;; stops and restarts.  The specified paths *should* cover the
  ;; common/reasonable locations....
  (->
   session
   (directory/directories
    (install-dirs (session/packager session))
    :mode "0770" :owner "couchdb" :group "couchdb")
   (package/package "couchdb")
   ;; the package seems to start couch in some way that the init script
   ;; can't terminate it, so we kill it.
   (exec-script/exec-checked-script
    "Kill and re-start couchdb"
    (pkill couchdb)
    (pkill beam))))


(defn configure
  "Configure couchdb using a key->value map.

   Note that the configuration options mirror the couchdb ini file hierarchy
   documented here: http://wiki.apache.org/couchdb/Configurationfile_couch.ini"
  [session {:as option-keyvals}]
  (->
   session
   (for-> [[k v] option-keyvals
           :let [config-path (->> k (map #(name %)) (interpose "/"))
                 url (apply
                      str "http://localhost:5984/_config/" config-path)
                 v-json (str \' (json/json-str v) \')]]
          (exec-script/exec-script
           (curl -X PUT -d ~v-json ~url)))))

(defn couchdb
  "Ensures couchdb is installed (along with curl, as a basis for convenient
   configuration) optionally configuring it as specified.

   e.g. (couchdb
          [:query_server_config :reduce_limit] \"false\"
          [:couchdb :database_dir] \"/var/some/other/path\")

   If any options are provided, then the couch server will be restarted after
   the configuraiton is modified."
  [session & {:as option-keyvals}]
  (let [session (install session)]
    (-> session
        (package/package "curl")
        (service/service "couchdb" :action :restart)
        ;; the sleeps are here because couchdb doesn't actually start taking
        ;; sessions for a little bit -- it appears that the real process that's
        ;; forked off is beam which ramps up couchdb *after* it's forked.
        (exec-script/exec-script (sleep 2))
        (configure option-keyvals)
        (service/service "couchdb" :action :restart)
        (exec-script/exec-script
         (sleep 2)
         (echo "Checking that couchdb is alive...")
         (curl "http://localhost:5984")))))
