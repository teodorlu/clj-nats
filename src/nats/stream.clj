(ns nats.stream
  (:require [nats.cluster :as cluster]
            [nats.message :as message])
  (:import (io.nats.client.api CompressionOption ConsumerLimits DiscardPolicy External
                               Placement Republish RetentionPolicy SourceBase
                               SourceInfoBase StorageType StreamConfiguration
                               StreamInfo StreamInfoOptions StreamInfoOptions$Builder
                               StreamState Subject SubjectTransform)))

(def retention-policies
  {:nats.retention-policy/limits RetentionPolicy/Limits
   :nats.retention-policy/work-queue RetentionPolicy/WorkQueue
   :nats.retention-policy/interest RetentionPolicy/Interest})

(def retention-policy->k
  (into {} (map (juxt second first) retention-policies)))

(def discard-policies
  {:nats.discard-policy/new DiscardPolicy/New
   :nats.discard-policy/old DiscardPolicy/Old})

(def discard-policy->k
  (into {} (map (juxt second first) discard-policies)))

(def compression-options
  {:nats.compression-option/none CompressionOption/None
   :nats.compression-option/s2 CompressionOption/S2})

(def compression-option->k
  (into {} (map (juxt second first) compression-options)))

(def storage-types
  {:nats.storage-type/file StorageType/File
   :nats.storage-type/memory StorageType/Memory})

(def storage-type->k
  (into {} (map (juxt second first) storage-types)))

(defn map->stream-configuration [{:keys [name
                                         description
                                         subjects
                                         retention-policy
                                         allow-direct-access?
                                         allow-rollup?
                                         deny-delete?
                                         deny-purge?
                                         max-age
                                         max-bytes
                                         max-consumers
                                         max-messages
                                         max-messages-per-subject
                                         max-msg-size
                                         replicas]}]
  (cond-> (StreamConfiguration/builder)
    name (.name name)
    description (.name description)
    subjects (.subjects (into-array String subjects))
    retention-policy (.retentionPolicy (retention-policies retention-policy))
    (boolean? allow-direct-access?) (.allowDirect allow-direct-access?)
    (boolean? allow-rollup?) (.allowRollup allow-rollup?)
    (boolean? deny-delete?) (.denyDelete deny-delete?)
    (boolean? deny-purge?) (.denyPurge deny-purge?)
    max-age (.maxAge max-age)
    max-bytes (.maxBytes max-bytes)
    max-consumers (.maxConsumers max-consumers)
    max-messages (.maxMessages max-messages)
    max-messages-per-subject (.maxMessagesPerSubject max-messages-per-subject)
    max-msg-size (.maxMsgSize max-msg-size)
    replicas (.replicas replicas)
    :always (.build)))

(defn ^:export get-stream-info-object [conn stream-name & [{:keys [include-deleted-details?
                                                                   filter-subjects]}]]
  (-> (.jetStreamManagement conn)
      (.getStreamInfo stream-name
                      (cond-> ^StreamInfoOptions$Builder (StreamInfoOptions/builder)
                        include-deleted-details? (.deletedDetails)
                        (seq filter-subjects) (.filterSubjects filter-subjects)
                        :always (.build)))))

(defn ^:export get-cluster-info [conn stream-name & [options]]
  (some-> (get-stream-info-object conn stream-name options)
          .getClusterInfo
          cluster/cluster-info->map))

(defn subject-transform->map [^SubjectTransform transform]
  {:destination (.getDestionation transform)
   :source (.getSource transform)})

(defn external->map [^External external]
  {:api (.getApi external)
   :deliver (.getDeliver external)})

(defn source-base->map [^SourceBase mirror]
  (let [external (some-> (.getExternal mirror) external->map)]
    (cond-> {:filter-subject (.getFilterSubject mirror)
             :name (.getName mirror)
             :source-name (.getSourceName mirror)
             :start-seq (.getStartSeq mirror)
             :start-time (.getStartTime mirror)
             :subject-transforms (map subject-transform->map (.getSubjectTransforms mirror))}
      external (assoc :external external))))

(defn consumer-limits->map [^ConsumerLimits consumer-limits]
  (let [inactive-threshold (.getInactiveThreshold consumer-limits)
        max-ack-pending (.getMaxAckPending consumer-limits)]
    (cond-> {}
      inactive-threshold (assoc :inactive-threshold inactive-threshold)
      max-ack-pending (assoc :max-ack-pending max-ack-pending))))

(defn placement->map [^Placement placement]
  {:cluster (.getCluster placement)
   :tags (seq (.getTags placement))})

(defn republish->map [^Republish republish]
  {:destination (.getDestionation republish)
   :source (.getSource republish)
   :headers-only? (.isHeadersOnly republish)})

(defn configuration->map [^StreamConfiguration config]
  (let [description (.getDescription config)
        mirror (some-> (.getMirror config) source-base->map)
        placement (some-> (.getPlacement config) placement->map)
        republish (some-> (.getRepublish config) republish->map)
        sources (for [source (.getSources config)]
                  (source-base->map source))
        subject-transform (some-> (.getSubjectTransform config) subject-transform->map)
        template-owner (.getTemplateOwner config)]
    (cond-> {:allow-direct? (.getAllowDirect config)
             :allow-rollup? (.getAllowRollup config)
             :compression-option (compression-option->k (.getCompressionOption config))
             :consumer-limits (consumer-limits->map (.getConsumerLimits config))
             :deny-delete? (.getDenyDelete config)
             :deny-purge? (.getDenyPurge config)
             :discard-policy (discard-policy->k (.getDiscardPolicy config))
             :duplicate-window (.getDuplicateWindow config)
             :first-sequence (.getFirstSequence config)
             :max-age (.getMaxAge config)
             :maxBytes (.getMaxBytes config)
             :max-consumers (.getMaxConsumers config)
             :max-msgs (.getMaxMsgs config)
             :max-msg-size (.getMaxMsgSize config)
             :max-msgs-per-subject (.getMaxMsgsPerSubject config)
             :metadata (into {} (.getMetadata config))
             :mirror-direct? (.getMirrorDirect config)
             :name (.getName config)
             :no-ack? (.getNoAck config)
             :replicas (.getReplicas config)
             :retention-policy (retention-policy->k (.getRetentionPolicy config))
             :sealed? (.getSealed config)
             :storage-type (storage-type->k (.getStorageType config))
             :subjects (seq (.getSubjects config))
             :discard-new-per-subject? (.isDiscardNewPerSubject config)}
      description (assoc :description description)
      mirror (assoc :mirror mirror)
      placement (assoc :placement placement)
      republish (assoc :republish republish)
      (seq sources) (assoc :sources sources)
      subject-transform (assoc :subject-transform subject-transform)
      template-owner (assoc :template-owner template-owner))))

(defn ^:export get-config [conn stream-name & [options]]
  (-> (get-stream-info-object conn stream-name options)
      .getConfiguration
      configuration->map))

(defn source-info->map [^SourceInfoBase info]
  (let [error (.getError info)
        external (some-> (.getExternal info) external->map)
        subject-transforms (map subject-transform->map (.getSubjectTransforms info))]
    (cond-> {:active (.getActive info)
             :lag (.getLag info)
             :name (.getName info)}
      error (assoc :error error)
      external (assoc :external external)
      (seq subject-transforms) (assoc :subject-transforms subject-transforms))))

(defn ^:export get-mirror-info [conn stream-name & [options]]
  (-> (get-stream-info-object conn stream-name options)
      .getMirrorInfo
      source-info->map))

(defn stream-state->map [^StreamState state]
  {:byte-count (.getByteCount state)
   :consumer-count (.getConsumerCount state)
   :deleted (into [] (.getDeleted state))
   :deleted-count (.getDeletedCount state)
   :first-sequence-number (.getFirstSequence state)
   :first-time (.getFirstTime state)
   :last-time (.getLastTime state)
   :message-count (.getMsgCount state)
   :subject-count (.getSubjectCount state)
   :subjects (for [^Subject subject (.getSubjects state)]
               {:count (.getCount subject)
                :name (.getName subject)})})

(defn ^:export get-stream-state [conn stream-name & [options]]
  (-> (get-stream-info-object conn stream-name options)
      .getStreamState
      stream-state->map))

(defn stream-info->map [^StreamInfo info]
  (let [source-infos (map source-info->map (.getSourceInfos info))
        cluster-info (.getClusterInfo info)
        mirror-info (.getMirrorInfo info)
        stream-state (.getStreamState info)]
    (cond-> {:create-time (.getCreateTime info)
             :configuration (configuration->map (.getConfiguration info))
             :timestamp (.getTimestamp info)}
      (seq source-infos) (assoc :source-infos source-infos)
      cluster-info (assoc :cluster-info (cluster/cluster-info->map cluster-info))
      mirror-info (assoc :mirror-info (source-info->map mirror-info))
      stream-state (assoc :stream-state (stream-state->map stream-state)))))

(defn ^:export get-stream-info [conn stream-name & [options]]
  (-> (get-stream-info-object conn stream-name options)
      stream-info->map))

(defn ^:export get-stream-names [conn & [{:keys [subject-filter]}]]
  (if subject-filter
    (.getStreamNames (.jetStreamManagement conn) subject-filter)
    (.getStreamNames (.jetStreamManagement conn))))

(defn ^:export get-streams [conn & [{:keys [subject-filter]}]]
  (->> (if subject-filter
         (.getStreams (.jetStreamManagement conn) subject-filter)
         (.getStreams (.jetStreamManagement conn)))
       (map stream-info->map)))

(defn ^{:style/indent 1 :export true} create-stream
  "Adds a stream. See `map->stream-configuration` for valid options in `config`."
  [conn config]
  (-> (.jetStreamManagement conn)
      (.addStream (map->stream-configuration config))
      stream-info->map))

(defn ^{:style/indent 1 :export true} update-stream
  "Updates a stream. See `map->stream-configuration` for valid options in `config`."
  [conn config]
  (-> (.jetStreamManagement conn)
      (.updateStream (map->stream-configuration config))
      stream-info->map))

(defn ^{:style/indent 1 :export true} publish
  "Publish a message to a JetStream subject. Performs publish acking if the stream
   requires it. Use `nats.core/publish` for regular PubSub messaging.

  message is a map of:

  - `:subject` - The subject to publish to
  - `:data` - The message data. Can be any Clojure value
  - `:headers` - An optional map of string keys to string (or collection of
                 string) values to set as meta-data on the message.
  - `:reply-to` - An optional reply-to subject."
  [conn message]
  (assert (not (nil? (:subject message))) "Can't publish without data")
  (assert (not (nil? (:data message))) "Can't publish nil data")
  (->> (message/build-message message)
       (.publish (.jetStream conn))))
