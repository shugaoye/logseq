(ns frontend.commands
  "Provides functionality for commands and advanced commands"
  (:require [clojure.string :as string]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db.utils :as db-utils]
            [frontend.extensions.video.youtube :as youtube]
            [frontend.handler.draw :as draw]
            [frontend.handler.notification :as notification]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.util.cursor :as cursor]
            [frontend.util.priority :as priority]
            [frontend.handler.file-based.property :as file-property-handler]
            [frontend.handler.property.util :as pu]
            [frontend.handler.property.file :as property-file]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [logseq.common.config :as common-config]
            [logseq.graph-parser.property :as gp-property]
            [logseq.common.util :as common-util]
            [logseq.common.util.block-ref :as block-ref]
            [logseq.common.util.page-ref :as page-ref]
            [promesa.core :as p]
            [frontend.handler.file-based.status :as file-based-status]
            [frontend.handler.db-based.property :as db-property-handler]))

;; TODO: move to frontend.handler.editor.commands

(defonce angle-bracket "<")
(defonce hashtag "#")
(defonce colon ":")
(defonce command-trigger "/")
(defonce *current-command (atom nil))

(def query-doc
  [:div {:on-mouse-down (fn [e] (.stopPropagation e))}
   [:div.font-medium.text-lg.mb-2 "Query examples:"]
   [:ul.mb-1
    [:li.mb-1 [:code "{{query #tag}}"]]
    [:li.mb-1 [:code "{{query [[page]]}}"]]
    [:li.mb-1 [:code "{{query \"full-text search\"}}"]]
    [:li.mb-1 [:code "{{query (and [[project]] (task NOW LATER))}}"]]
    [:li.mb-1 [:code "{{query (or [[page 1]] [[page 2]])}}"]]
    [:li.mb-1 [:code "{{query (and (between -7d +7d) (task DONE))}}"]]
    [:li.mb-1 [:code "{{query (property key value)}}"]]
    [:li.mb-1 [:code "{{query (page-tags #tag)}}"]]]

   [:p "Check more examples at "
    [:a {:href "https://docs.logseq.com/#/page/queries"
         :target "_blank"}
     "Queries documentation"]
    "."]])

(defn link-steps []
  [[:editor/input (str command-trigger "link")]
   [:editor/show-input [{:command :link
                         :id :link
                         :placeholder "Link"
                         :autoFocus true}
                        {:command :link
                         :id :label
                         :placeholder "Label"}]]])

(defn image-link-steps []
  [[:editor/input (str command-trigger "link")]
   [:editor/show-input [{:command :image-link
                         :id :link
                         :placeholder "Link"
                         :autoFocus true}
                        {:command :image-link
                         :id :label
                         :placeholder "Label"}]]])

(defn zotero-steps []
  [[:editor/input (str command-trigger "zotero")]
   [:editor/show-zotero]])

(def *extend-slash-commands (atom []))

(defn register-slash-command [cmd]
  (swap! *extend-slash-commands conj cmd))

(defn ->marker
  [marker]
  [[:editor/clear-current-slash]
   [:editor/set-status marker]
   [:editor/move-cursor-to-end]])

(defn ->priority
  [priority]
  [[:editor/clear-current-slash]
   [:editor/set-priority priority]
   [:editor/move-cursor-to-end]])

(defn ->inline
  [type]
  (let [template (util/format "@@%s: @@"
                              type)]
    [[:editor/input template {:last-pattern command-trigger
                              :backward-pos 2}]]))

(defn file-based-embed-page
  []
  [[:editor/input "{{embed [[]]}}" {:last-pattern command-trigger
                                    :backward-pos 4}]
   [:editor/search-page :embed]])

(defn file-based-embed-block
  []
  [[:editor/input "{{embed (())}}" {:last-pattern command-trigger
                                    :backward-pos 4}]
   [:editor/search-block :embed]])

(defn file-based-statuses
  []
  (let [workflow (state/get-preferred-workflow)]
    (if (= :now workflow)
      ["LATER" "NOW" "TODO" "DOING" "DONE" "WAITING" "CANCELED"]
      ["TODO" "DOING" "LATER" "NOW" "DONE" "WAITING" "CANCELED"])))

(defn db-based-statuses
  []
  (map (fn [id] (get-in (db/entity [:block/uuid id]) [:block/schema :value]))
    (pu/get-closed-property-values "status")))

(defn db-based-embed-page
  []
  [[:editor/input "[[]]" {:last-pattern command-trigger
                          :backward-pos 2}]
   [:editor/search-page :embed]])

(defn db-based-embed-block
  []
  [[:editor/input "(())" {:last-pattern command-trigger
                          :backward-pos 2}]
   [:editor/search-block :embed]])

(defn get-statuses
  []
  (let [result (->>
                (if (config/db-based-graph? (state/get-current-repo))
                  (db-based-statuses)
                  (file-based-statuses))
                (mapv (fn [m] [m (->marker m) (str "Set status to " m)])))]
    (when (seq result)
      (update result 0 (fn [v] (conj v "TASK"))))))

(defn file-based-priorities
  []
  ["A" "B" "C"])

(defn db-based-priorities
  []
  (map (fn [id] (get-in (db/entity [:block/uuid id]) [:block/schema :value]))
    (pu/get-closed-property-values "priority")))

(defn get-priorities
  []
  (let [result (->>
                (if (config/db-based-graph? (state/get-current-repo))
                  (db-based-priorities)
                  (file-based-priorities))
                (mapv (fn [item] [item (->priority item) (str "Set priority to " item)])))]
    (when (seq result)
      (update result 0 (fn [v] (conj v "PRIORITY"))))))

;; Credits to roamresearch.com

(defn- ->heading
  [heading]
  [[:editor/clear-current-slash]
   [:editor/set-heading heading]
   [:editor/move-cursor-to-end]])

(defn- headings
  []
  (mapv (fn [level]
          (let [heading (str "h" level)]
            [heading (->heading level)])) (range 1 7)))

(defonce *matched-commands (atom nil))
(defonce *initial-commands (atom nil))

(defn ->block
  ([type]
   (->block type nil))
  ([type optional]
   (let [format (get (state/get-edit-block) :block/format)
         markdown-src? (and (= format :markdown)
                            (= (string/lower-case type) "src"))
         [left right] (cond
                        markdown-src?
                        ["```" "\n```"]

                        :else
                        (->> ["#+BEGIN_%s" "\n#+END_%s"]
                             (map #(util/format %
                                                (string/upper-case type)))))
         template (str
                   left
                   (if optional (str " " optional) "")
                   "\n"
                   right)
         backward-pos (if (= type "src")
                        (+ 1 (count right))
                        (count right))]
     [[:editor/input template {:type "block"
                               :last-pattern angle-bracket
                               :backward-pos backward-pos}]])))

(defn ->properties
  []
  [[:editor/clear-current-bracket]
   [:editor/insert-properties]
   [:editor/move-cursor-to-properties]])

;; https://orgmode.org/manual/Structure-Templates.html
(defn block-commands-map
  []
  (->>
   (concat
    [["Quote" (->block "quote")
      "Add a quote"
      "More"]
     ["Src" (->block "src" "")]
     ["Query" (->block "query")]
     ["Latex export" (->block "export" "latex")]
     ;; FIXME: current page's format
     (when (= :org (state/get-preferred-format))
       ["Properties" (->properties)])
     ["Note" (->block "note")]
     ["Tip" (->block "tip")]
     ["Important" (->block "important")]
     ["Caution" (->block "caution")]
     ["Pinned" (->block "pinned")]
     ["Warning" (->block "warning")]
     ["Example" (->block "example")]
     ["Export" (->block "export")]
     ["Verse" (->block "verse")]
     ["Ascii" (->block "export" "ascii")]
     ["Center" (->block "center")]
     ["Comment" (->block "comment")]]

    ;; Allow user to modify or extend, should specify how to extend.
    (state/get-commands))
   (remove nil?)
   (util/distinct-by-last-wins first)))

(defn ^:large-vars/cleanup-todo commands-map
  [get-page-ref-text]
  (let [db? (config/db-based-graph? (state/get-current-repo))
        embed-page (if db? db-based-embed-page file-based-embed-page)
        embed-block (if db? db-based-embed-block file-based-embed-block)]
    (->>
     (concat
    ;; basic
      [["Page reference"
        [[:editor/input page-ref/left-and-right-brackets {:backward-pos 2}]
         [:editor/search-page]]
        "Create a backlink to a page"
        "BASIC"]
       ["Page embed" (embed-page) "Embed a page here"]
       ["Block reference" [[:editor/input block-ref/left-and-right-parens {:backward-pos 2}]
                           [:editor/search-block :reference]] "Create a backlink to a block"]
       ["Block embed" (embed-block) "Embed a block here" "Embed a block here"]
       ["Link" (link-steps) "Create a HTTP link"]
       ["Image link" (image-link-steps) "Create a HTTP link to a image"]
       (when (state/markdown?)
         ["Underline" [[:editor/input "<ins></ins>"
                        {:last-pattern command-trigger
                         :backward-pos 6}]] "Create a underline text decoration"])
       (when-not db?
         ["Template" [[:editor/input command-trigger nil]
                      [:editor/search-template]] "Insert a created template here"])
       (cond
         (and (util/electron?) (config/local-file-based-graph? (state/get-current-repo)))

         ["Upload an asset" [[:editor/click-hidden-file-input :id]] "Upload file types like image, pdf, docx, etc.)"])]

       ;; ["Upload an image" [[:editor/click-hidden-file-input :id]]]

      (headings)

    ;; time & date

      [["Tomorrow"
        #(get-page-ref-text (date/tomorrow))
        "Insert the date of tomorrow"
        "TIME & DATE"]
       ["Yesterday" #(get-page-ref-text (date/yesterday)) "Insert the date of yesterday"]
       ["Today" #(get-page-ref-text (date/today)) "Insert the date of today"]
       ["Current time" #(date/get-current-time) "Insert current time"]
       ["Date picker" [[:editor/show-date-picker]] "Pick a date and insert here"]]

      ;; order list
      [["Number list"
        [[:editor/clear-current-slash]
         [:editor/toggle-own-number-list]]
        "Number list"
        "LIST TYPE"]
       ["Number children" [[:editor/clear-current-slash]
                           [:editor/toggle-children-number-list]] "Number children"]]

    ;; task management
      (get-statuses)
      [["Deadline" [[:editor/clear-current-slash]
                    [:editor/set-deadline]]]
       ["Scheduled" [[:editor/clear-current-slash]
                     [:editor/set-scheduled]]]]

    ;; priority
      (get-priorities)

    ;; advanced

      [["Query"
        [[:editor/input "{{query }}" {:backward-pos 2}]
         [:editor/exit]]
        query-doc
        "ADVANCED"]
       ["Zotero" (zotero-steps) "Import Zotero journal article"]
       ["Query function" [[:editor/input "{{function }}" {:backward-pos 2}]] "Create a query function"]
       ["Calculator" [[:editor/input "```calc\n\n```" {:type "block"
                                                       :backward-pos 4}]
                      [:codemirror/focus]] "Insert a calculator"]
       ["Draw" (fn []
                 (let [file (draw/file-name)
                       path (str common-config/default-draw-directory "/" file)
                       text (page-ref/->page-ref path)]
                   (p/let [_ (draw/create-draw-with-default-content path)]
                     (println "draw file created, " path))
                   text)) "Draw a graph with Excalidraw"]
       ["Embed HTML " (->inline "html")]

       ["Embed Video URL" [[:editor/input "{{video }}" {:last-pattern command-trigger
                                                        :backward-pos 2}]]]

       ["Embed Youtube timestamp" [[:youtube/insert-timestamp]]]

       ["Embed Twitter tweet" [[:editor/input "{{tweet }}" {:last-pattern command-trigger
                                                            :backward-pos 2}]]]
       ["Add new property" [[:editor/clear-current-slash]
                            [:editor/new-property]]]

       ["Code block" [[:editor/input "```\n```\n" {:type            "block"
                                                   :backward-pos    5
                                                   :only-breakline? true}]
                      [:editor/select-code-block-mode]] "Insert code block"]]

      @*extend-slash-commands
    ;; Allow user to modify or extend, should specify how to extend.

      (state/get-commands)
      (state/get-plugins-slash-commands))
     (remove nil?)
     (util/distinct-by-last-wins first))))

(defn init-commands!
  [get-page-ref-text]
  (let [commands (commands-map get-page-ref-text)]
    (reset! *initial-commands commands)
    (reset! *matched-commands commands)))

(defonce *matched-block-commands (atom (block-commands-map)))

(defn reinit-matched-commands!
  []
  (reset! *matched-commands @*initial-commands))

(defn reinit-matched-block-commands!
  []
  (reset! *matched-block-commands (block-commands-map)))

(defn restore-state
  []
  (state/clear-editor-action!)
  (reinit-matched-commands!)
  (reinit-matched-block-commands!))

(defn insert!
  [id value
   {:keys [last-pattern postfix-fn backward-pos end-pattern backward-truncate-number
           command only-breakline?]
    :as _option}]
  (when-let [input (gdom/getElement id)]
    (let [last-pattern (when-not (= last-pattern :skip-check)
                         (when-not backward-truncate-number
                           (or last-pattern command-trigger)))
          edit-content (gobj/get input "value")
          current-pos (cursor/pos input)
          current-pos (or
                       (when (and end-pattern (string? end-pattern))
                         (when-let [i (string/index-of (common-util/safe-subs edit-content current-pos) end-pattern)]
                           (+ current-pos i)))
                       current-pos)
          orig-prefix (subs edit-content 0 current-pos)
          postfix (subs edit-content current-pos)
          postfix (if postfix-fn (postfix-fn postfix) postfix)
          space? (let [space? (when (and last-pattern orig-prefix)
                                (let [s (when-let [last-index (string/last-index-of orig-prefix last-pattern)]
                                          (common-util/safe-subs orig-prefix 0 last-index))]
                                  (not
                                   (or
                                    (and (= :page-ref command)
                                         (util/cjk-string? value)
                                         (or (util/cjk-string? (str (last orig-prefix)))
                                             (util/cjk-string? (str (first postfix)))))
                                    (and s
                                         (string/ends-with? s "(")
                                         (or (string/starts-with? last-pattern block-ref/left-parens)
                                             (string/starts-with? last-pattern page-ref/left-brackets)))
                                    (and s (string/starts-with? s "{{embed"))
                                    (and s (= (last s) \#) (string/starts-with? last-pattern "[["))
                                    (and last-pattern
                                         (or (string/ends-with? last-pattern gp-property/colons)
                                             (string/starts-with? last-pattern gp-property/colons)))))))]
                   (if (and space? (or (string/starts-with? last-pattern "#[[")
                                       (string/starts-with? last-pattern "```")))
                     false
                     space?))
          prefix (cond
                   (and backward-truncate-number (integer? backward-truncate-number))
                   (str (common-util/safe-subs orig-prefix 0 (- (count orig-prefix) backward-truncate-number))
                        (when-not (zero? backward-truncate-number)
                          value))

                   (string/blank? last-pattern)
                   (if space?
                     (util/concat-without-spaces orig-prefix value)
                     (str orig-prefix value))

                   :else
                   (util/replace-last last-pattern orig-prefix value space?))
          postfix (cond-> postfix
                    (and only-breakline? postfix
                         (= (get postfix 0) "\n"))
                    (string/replace-first "\n" ""))
          new-value (cond
                      (string/blank? postfix)
                      prefix

                      space?
                      (util/concat-without-spaces prefix postfix)

                      :else
                      (str prefix postfix))
          new-pos (- (count prefix)
                     (or backward-pos 0))]
      (when-not (and (not (string/blank? value))
                     (string/blank? new-value))
        (state/set-block-content-and-last-pos! id new-value new-pos)
        (cursor/move-cursor-to input new-pos)))))

(defn simple-insert!
  [id value
   {:keys [backward-pos forward-pos check-fn]
    :as _option}]
  (let [input (gdom/getElement id)
        edit-content (gobj/get input "value")
        current-pos (cursor/pos input)
        prefix (subs edit-content 0 current-pos)
        surfix (subs edit-content current-pos)
        new-value (str prefix
                       value
                       surfix)
        new-pos (- (+ (count prefix)
                      (count value)
                      (or forward-pos 0))
                   (or backward-pos 0))]
    (state/set-edit-content! (state/get-edit-input-id)
                             (str prefix value))
    ;; HACK: save scroll-pos of current pos, then add trailing content
    (let [scroll-container (util/nearest-scrollable-container input)
          scroll-pos (.-scrollTop scroll-container)]
      (state/set-block-content-and-last-pos! id new-value new-pos)
      (cursor/move-cursor-to input new-pos)
      (set! (.-scrollTop scroll-container) scroll-pos)
      (when check-fn
        (check-fn new-value (dec (count prefix)) new-pos)))))

(defn simple-replace!
  [id value selected
   {:keys [backward-pos forward-pos check-fn]
    :as _option}]
  (let [selected? (not (string/blank? selected))
        input (gdom/getElement id)
        edit-content (gobj/get input "value")]
    (when edit-content
      (let [current-pos (cursor/pos input)
            prefix (subs edit-content 0 current-pos)
            postfix (if selected?
                      (string/replace-first (subs edit-content current-pos)
                                            selected
                                            "")
                      (subs edit-content current-pos))
            new-value (str prefix value postfix)
            new-pos (- (+ (count prefix)
                          (count value)
                          (or forward-pos 0))
                       (or backward-pos 0))]
        (state/set-block-content-and-last-pos! id new-value new-pos)
        (cursor/move-cursor-to input new-pos)
        (when selected?
          (.setSelectionRange input new-pos (+ new-pos (count selected))))
        (when check-fn
          (check-fn new-value (dec (count prefix))))))))

(defn delete-pair!
  [id]
  (let [input (gdom/getElement id)
        edit-content (gobj/get input "value")
        current-pos (cursor/pos input)
        prefix (subs edit-content 0 (dec current-pos))
        new-value (str prefix
                       (subs edit-content (inc current-pos)))
        new-pos (count prefix)]
    (state/set-block-content-and-last-pos! id new-value new-pos)
    (cursor/move-cursor-to input new-pos)))

(defn delete-selection!
  [id]
  (let [input (gdom/getElement id)
        edit-content (gobj/get input "value")
        start (util/get-selection-start input)
        end (util/get-selection-end input)]
    (when-not (= start end)
      (let [prefix (subs edit-content 0 start)
            new-value (str prefix
                           (subs edit-content end))
            new-pos (count prefix)]
        (state/set-block-content-and-last-pos! id new-value new-pos)
        (cursor/move-cursor-to input new-pos)))))

(defn get-matched-commands
  ([text]
   (get-matched-commands text @*initial-commands))
  ([text commands]
   (search/fuzzy-search commands text
                        :extract-fn first
                        :limit 50)))

(defmulti handle-step first)

(defmethod handle-step :editor/hook [[_ event {:keys [pid uuid] :as payload}] format]
  (plugin-handler/hook-plugin-editor event (merge payload {:format format :uuid (or uuid (:block/uuid (state/get-edit-block)))}) pid))

(defmethod handle-step :editor/input [[_ value option]]
  (when-let [input-id (state/get-edit-input-id)]
    (let [type (:type option)
          input (gdom/getElement input-id)
          beginning-of-line? (or (cursor/beginning-of-line? input)
                                 (= 1 (:pos (:pos (state/get-editor-action-data)))))
          value (if (and (contains? #{"block" "properties"} type)
                         (not beginning-of-line?))
                  (str "\n" value)
                  value)]
      (insert! input-id value option)
      (state/clear-editor-action!))))

(defmethod handle-step :editor/cursor-back [[_ n]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (cursor/move-cursor-backward current-input n))))

(defmethod handle-step :editor/cursor-forward [[_ n]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (cursor/move-cursor-forward current-input n))))

(defmethod handle-step :editor/move-cursor-to-end [[_]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (cursor/move-cursor-to-end current-input))))

(defmethod handle-step :editor/restore-saved-cursor [[_]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (cursor/move-cursor-to current-input (state/get-editor-last-pos)))))

(defmethod handle-step :editor/clear-current-slash [[_ space?]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [edit-content (gobj/get current-input "value")
            current-pos (cursor/pos current-input)
            prefix (subs edit-content 0 current-pos)
            prefix (util/replace-last command-trigger prefix "" (boolean space?))
            new-value (str prefix
                           (subs edit-content current-pos))]
        (state/set-block-content-and-last-pos! input-id
                                               new-value
                                               (count prefix))))))

(defmethod handle-step :editor/clear-current-bracket [[_ space?]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [edit-content (gobj/get current-input "value")
            current-pos (cursor/pos current-input)
            prefix (subs edit-content 0 current-pos)
            prefix (util/replace-last angle-bracket prefix "" (boolean space?))
            new-value (str prefix
                           (subs edit-content current-pos))]
        (state/set-block-content-and-last-pos! input-id
                                               new-value
                                               (count prefix))))))

(defn compute-pos-delta-when-change-marker
  [edit-content marker pos]
  (let [old-marker (some->> (first (util/safe-re-find file-based-status/bare-marker-pattern edit-content))
                            (string/trim))
        pos-delta (- (count marker)
                     (count old-marker))
        pos-delta (cond (string/blank? old-marker)
                        (inc pos-delta)
                        (string/blank? marker)
                        (dec pos-delta)

                        :else
                        pos-delta)]
    (max (+ pos pos-delta) 0)))

(defn- file-based-set-status
  [marker format]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [edit-content (gobj/get current-input "value")
            slash-pos (:pos (:pos (state/get-editor-action-data)))
            [re-pattern new-line-re-pattern] (if (= :org format)
                                               [#"\*+\s" #"\n\*+\s"]
                                               [#"#+\s" #"\n#+\s"])
            pos (let [prefix (subs edit-content 0 (dec slash-pos))]
                  (if-let [matches (seq (util/re-pos new-line-re-pattern prefix))]
                    (let [[start-pos content] (last matches)]
                      (+ start-pos (count content)))
                    (count (util/safe-re-find re-pattern prefix))))
            new-value (str (subs edit-content 0 pos)
                           (string/replace-first (subs edit-content pos)
                                                 (file-based-status/marker-pattern format)
                                                 (str marker " ")))]
        (state/set-edit-content! input-id new-value)
        (let [new-pos (compute-pos-delta-when-change-marker
                       edit-content marker (dec slash-pos))]
          ;; TODO: any performance issue?
          (js/setTimeout #(cursor/move-cursor-to current-input new-pos) 10))))))

(defn- db-based-set-status
  [status]
  (when-let [block (state/get-edit-block)]
    (db-property-handler/batch-set-property-closed-value! [(:block/uuid block)] "status" status)))

(defmethod handle-step :editor/set-status [[_ status] format]
  (if (config/db-based-graph? (state/get-current-repo))
    (db-based-set-status status)
    (file-based-set-status status format)))

(defn- file-based-set-priority
  [priority]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [format (or (db/get-page-format (state/get-current-page)) (state/get-preferred-format))
            edit-content (gobj/get current-input "value")
            new-priority (util/format "[#%s]" priority)
            new-value (string/trim (priority/add-or-update-priority edit-content format new-priority))]
        (state/set-edit-content! input-id new-value)))))

(defn- db-based-set-priority
  [priority]
  (when-let [block (state/get-edit-block)]
    (db-property-handler/batch-set-property-closed-value! [(:block/uuid block)] "priority" priority)))

(defmethod handle-step :editor/set-priority [[_ priority] _format]
  (if (config/db-based-graph? (state/get-current-repo))
    (db-based-set-priority priority)
    (file-based-set-priority priority)))

(defmethod handle-step :editor/set-scheduled [[_]]
  (if (config/db-based-graph? (state/get-current-repo))
    (state/pub-event! [:editor/new-property "Scheduled"])
    (handle-step [:editor/show-date-picker :scheduled])))

(defmethod handle-step :editor/set-deadline [[_]]
  (if (config/db-based-graph? (state/get-current-repo))
    (state/pub-event! [:editor/new-property "Deadline"])
    (handle-step [:editor/show-date-picker :deadline])))

(defmethod handle-step :editor/insert-properties [[_ _] _format]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [format (or (db/get-page-format (state/get-current-page)) (state/get-preferred-format))
            edit-content (gobj/get current-input "value")
            new-value (file-property-handler/insert-property format edit-content "" "")]
        (state/set-edit-content! input-id new-value)))))

(defmethod handle-step :editor/move-cursor-to-properties [[_]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [format (or (db/get-page-format (state/get-current-page)) (state/get-preferred-format))]
        (property-file/goto-properties-end-when-file-based format current-input)
        (cursor/move-cursor-backward current-input 3)))))

(defonce markdown-heading-pattern #"^#+\s+")
(defn set-markdown-heading
  [content heading]
  (let [heading-str (apply str (repeat heading "#"))]
    (if (util/safe-re-find markdown-heading-pattern content)
      (string/replace-first content
                            markdown-heading-pattern
                            (str heading-str " "))
      (str heading-str " " (string/triml content)))))

(defn clear-markdown-heading
  [content]
  [:pre (string? content)]
  (string/replace-first content
                        markdown-heading-pattern
                        ""))

(defmethod handle-step :editor/set-heading [[_ heading]]
  (when-let [input-id (state/get-edit-input-id)]
    (when-let [current-input (gdom/getElement input-id)]
      (let [current-block (state/get-edit-block)
            format (:block/format current-block)]
        (if (= format :markdown)
          (let [edit-content (gobj/get current-input "value")
                new-content (set-markdown-heading edit-content heading)]
            (state/set-edit-content! input-id new-content))
          (state/pub-event! [:editor/set-org-mode-heading current-block heading]))))))

(defmethod handle-step :editor/search-page [_]
  (state/set-editor-action! :page-search))

(defmethod handle-step :editor/search-page-hashtag [[_]]
  (state/set-editor-action! :page-search-hashtag))

(defmethod handle-step :editor/search-block [[_ _type]]
  (state/set-editor-action! :block-search))

(defmethod handle-step :editor/search-template [[_]]
  (state/set-editor-action! :template-search))

(defmethod handle-step :editor/show-input [[_ option]]
  (state/set-editor-show-input! option))

(defmethod handle-step :editor/show-zotero [[_]]
  (state/set-editor-action! :zotero))

(defn insert-youtube-timestamp
  []
  (let [input-id (state/get-edit-input-id)
        macro (youtube/gen-youtube-ts-macro)]
    (when-let [input (gdom/getElement input-id)]
      (when macro
        (util/insert-at-current-position! input (str macro " "))))))

(defmethod handle-step :youtube/insert-timestamp [[_]]
  (let [input-id (state/get-edit-input-id)
        macro (youtube/gen-youtube-ts-macro)]
    (insert! input-id macro {})))

(defmethod handle-step :editor/toggle-children-number-list [[_]]
  (when-let [block (state/get-edit-block)]
    (state/pub-event! [:editor/toggle-children-number-list block])))

(defmethod handle-step :editor/toggle-own-number-list [[_]]
  (when-let [block (state/get-edit-block)]
    (state/pub-event! [:editor/toggle-own-number-list block])))

(defmethod handle-step :editor/remove-own-number-list [[_]]
  (when-let [block (state/get-edit-block)]
    (state/pub-event! [:editor/remove-own-number-list block])))

(defmethod handle-step :editor/show-date-picker [[_ type]]
  (if (and
       (contains? #{:scheduled :deadline} type)
       (string/blank? (gobj/get (state/get-input) "value")))
    (do
      (notification/show! [:div "Please add some content first."] :warning)
      (restore-state))
    (do
      (state/set-timestamp-block! nil)
      (state/set-editor-action! :datepicker))))

(defmethod handle-step :editor/select-code-block-mode [[_]]
  (-> (p/delay 50)
      (p/then
        (fn []
          (when-let [input (state/get-input)]
            ;; update action cursor position
            (state/set-editor-action-data! {:pos (cursor/get-caret-pos input)})
            (state/set-editor-action! :select-code-block-mode))))))

(defmethod handle-step :editor/click-hidden-file-input [[_ _input-id]]
  (when-let [input-file (gdom/getElement "upload-file")]
    (.click input-file)))

(defmethod handle-step :editor/exit [[_]]
  (state/clear-edit!))

(defmethod handle-step :editor/new-property [[_]]
  (state/pub-event! [:editor/new-property]))

(defmethod handle-step :default [[type & _args]]
  (prn "No handler for step: " type))

(defn handle-steps
  [vector format]
  (doseq [step vector]
    (handle-step step format)))

(defn exec-plugin-simple-command!
  [pid {:keys [block-id] :as cmd} action]
  (let [format (and block-id (:block/format (db-utils/pull [:block/uuid block-id])))
        inputs (vector (conj action (assoc cmd :pid pid)))]
    (handle-steps inputs format)))
