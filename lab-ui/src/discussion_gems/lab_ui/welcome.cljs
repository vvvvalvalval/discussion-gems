(ns discussion-gems.lab-ui.welcome
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [rum.core :as rum]
            [goog.object]
            [goog.string]
            [goog.window]
            [cljs-http.client :as http]
            [cljs.core.async :as a :refer [<!]]))

(enable-console-print!)

(defonce a-state
  (atom {}))


(defn eval-remote-code
  [eval-req]
  (go
    (let [response
          (<! (http/post "http://localhost:9000"
                {:headers {"Accept" "application/transit+json"}
                 :with-credentials? false
                 :transit-params eval-req}))]
      ;; TODO error handling (Val, 30 Apr 2020)
      (-> response :body :result))))

(defn savel-label-and-load-next!
  [lbl-or-nil]
  (let [current-cmt (:comment-to-label @a-state)]
    (go
      (when-some
        [c
         (<!
           (eval-remote-code
             (merge
               {:remote-eval/require '[[discussion-gems.experiments.detecting-praise-comments]]}
               (if (some? lbl-or-nil)
                 {:remote-eval/data-bindings
                  {'c-name (-> current-cmt
                             (or (throw
                                   (ex-info "No comment to label!" {})))
                             :name)
                   'c (select-keys current-cmt
                        [:name
                         :link_id
                         :dgms_comment_submission
                         :body
                         :discussion-gems.experiments.detecting-praise-comments/sample-slice
                         :discussion-gems.experiments.detecting-praise-comments/presim-score])
                   'lbl lbl-or-nil}
                  :remote-eval/code-str
                  (pr-str
                    '(do
                       (discussion-gems.experiments.detecting-praise-comments/save-comment-label!
                         c-name
                         c
                         lbl)
                       (discussion-gems.experiments.detecting-praise-comments/next-comment-to-label)))}
                 {:remote-eval/code-str
                  (pr-str
                    '(discussion-gems.experiments.detecting-praise-comments/next-comment-to-label))}))))]
        (swap! a-state assoc
          :previous-comment current-cmt
          :comment-to-label c)))))


(comment

  (savel-label-and-load-next! nil)

  @a-state

  (-> @a-state :comment-to-label :dgms_body_html)

  (reset! a-state
    {:comment-to-label {:retrieved_on 1512860197,
                        :can_gild true,
                        :stickied false,
                        :subreddit_type "public",
                        :name "t1_dq4s3bc",
                        :dgms_comment_submission {:link_flair_text "Politique",
                                                  :name "t3_7ebx5g",
                                                  :permalink "/r/france/comments/7ebx5g/les_députés_adoptent_à_lunanimité_la_déchéance/",
                                                  :is_self false,
                                                  :title "Les députés adoptent, à l'unanimité, la déchéance des droits civiques, civils et de famille en cas de fraude fiscale",
                                                  :num_crossposts 0,
                                                  :score 53,
                                                  :url "https://mobile.twitter.com/Projet_Arcadie/status/932641573376069632",
                                                  :gilded 0,
                                                  :created_utc 1511210010,
                                                  :num_comments 46,
                                                  :media nil},
                        :permalink "/r/france/comments/7ebx5g/les_députés_adoptent_à_lunanimité_la_déchéance/dq4s3bc/",
                        :link_id "t3_7ebx5g",
                        :controversiality 0,
                        :dgms_body_html "<p>Bien sûr qu'on peut, mais l'immense majorité des cas de fraude sont découverts par les inspecteurs du ministère des finances, et ces derniers n'ont pas le droit de le signaler directement à la justice sans accord de leur hiérarchie.</p><p><a href='https://fr.wikipedia.org/wiki/R%C3%A9my_Garnier#De_multiples_sanctions_disciplinaires'>Et ça pose de gros problèmes</a></p><p>Bref si le gouvernement voulait vraiment faire peur aux gros fraudeurs, il laisserait les procureurs s'occuper des poursuites, et distribuer des peines de prison ferme avec mandat de dépôt, car ça, ça fait peur aux criminels en col blanc. Si la seule peine est une amende, alors ça revient à jouer au casino.</p>",
                        :subreddit_id "t5_2qhjz",
                        :edited false,
                        :author "byroot",
                        :dgms_n_formatting 1,
                        :distinguished nil,
                        :dgms_syntax_stats_fr {:n-sentences 3,
                                               :pos-freqs {"ADV" 7,
                                                           "ADJ" 8,
                                                           "CLO" 1,
                                                           "CLS" 2,
                                                           "CS" 3,
                                                           "PUNC" 10,
                                                           "CC" 5,
                                                           "P" 18,
                                                           "V" 9,
                                                           "DET" 16,
                                                           "N" 3,
                                                           "PRO" 4,
                                                           "NC" 24,
                                                           "VINF" 5,
                                                           "CLR" 1}},
                        :parent_id "t1_dq4rmpz",
                        :id "dq4s3bc",
                        :score 19,
                        :is_submitter false,
                        :author_flair_css_class nil,
                        :gilded 0,
                        :created_utc 1511251611,
                        :dgms_body_raw "Bien sûr qu'on peut, mais l'immense majorité des cas de fraude sont découverts par les inspecteurs du ministère des finances, et ces derniers n'ont pas le droit de le signaler directement à la justice sans accord de leur hiérarchie.

                                    Et ça pose de gros problèmes

                                    Bref si le gouvernement voulait vraiment faire peur aux gros fraudeurs, il laisserait les procureurs s'occuper des poursuites, et distribuer des peines de prison ferme avec mandat de dépôt, car ça, ça fait peur aux criminels en col blanc. Si la seule peine est une amende, alors ça revient à jouer au casino.

                                    ",
                        :author_flair_text nil,
                        :dgms_hyperlinks ["https://fr.wikipedia.org/wiki/R%C3%A9my_Garnier#De_multiples_sanctions_disciplinaires"],
                        :body "Bien sûr qu'on peut, mais l'immense majorité des cas de fraude sont découverts par les inspecteurs du ministère des finances, et ces derniers n'ont pas le droit de le signaler directement à la justice sans accord de leur hiérarchie.

                           [Et ça pose de gros problèmes](https://fr.wikipedia.org/wiki/R%C3%A9my_Garnier#De_multiples_sanctions_disciplinaires)

                           Bref si le gouvernement voulait vraiment faire peur aux gros fraudeurs, il laisserait les procureurs s'occuper des poursuites, et distribuer des peines de prison ferme avec mandat de dépôt, car ça, ça fait peur aux criminels en col blanc. Si la seule peine est une amende, alors ça revient à jouer au casino.",
                        :subreddit "france"}})

  *e)

(defn permalink
  [c]
  (str
    "https://www.reddit.com"
    (-> c :dgms_comment_submission :permalink)
    (:id c)
    "?context=8&depth=9"))

(rum/defc <comment-content>
  [c cited?]
  [:div {:class (-> "card"
                  (cond-> cited? (str " " "bg-light")))}
   [:div {:class "card-header"}
    [:h5 {:class "card-title"}
     [:a {:href (permalink c) :target "_blank"}
      [:pre (:name c)]]]
    (when-let [subm (and
                      (not cited?)
                      (:dgms_comment_submission c))]
      [:span
       "in "
       [:a {:href (str "https://www.reddit.com" (:permalink subm))
            :target "_blank"}
        ": "
        (:title subm)
        "(" [:code (:name subm)] ")"
        (when-some [flair (:link_flair_text subm)]
          [:span {:class "badge badge-secondary"} flair])]])]
   [:div {:class "card-body"}
    (:dgms_body__hiccup c)
    (when-not cited?
      (if-some [pc (:dgms_comment_parent c)]
        (<comment-content> pc true)
        (or
          (when-some [parent-id (:parent_id c)]
            (let [parent-is-comment? (goog.string/startsWith parent-id "t1_")]
              (when parent-is-comment?
                (<comment-content>
                  {:name parent-id
                   :dgms_body__hiccup
                   [:div "(Parent comment not available)"]}
                  true))))
          [:div])))]])

(rum/defc <comment-to-label>
  [c]
  (if (some? c)
    [:div
     [:div
      [:button {:class "btn btn-primary"
                :on-click (fn [_] (savel-label-and-load-next! 0.))} "0"]
      [:button {:class "btn btn-success"
                :on-click (fn [_] (savel-label-and-load-next! 1.))} "1"]
      [:button {:class "btn btn-secondary"
                :on-click (fn [_] (savel-label-and-load-next! 0.5))} "?"]]
     (<comment-content> c false)]
    [:div
     [:p "Loading a comment to label..."]]))


(defn return-to-previous-comment!
  []
  (swap! a-state
    (fn [state]
      (if-some [prev-c (:previous-comment state)]
        (assoc state
          :comment-to-label prev-c
          :previous-comment nil)
        state))))


(defn label-for-key
  [k]
  (case k
    "p" 1.
    "n" 0.
    "u" 0.5
    ("0" "1" "2" "3" "4" "5" "6" "7" "8" "9")
    (/ (goog.string/parseInt k) 10.)
    nil))

(defn on-key!
  [e]
  (let [k (goog.object/get e "key")]
    (case k
      "o" (when-some [url (some-> @a-state :comment-to-label
                            (permalink))]
            (goog.window/open url))
      "l" (return-to-previous-comment!)
      (when-some [lbl (label-for-key k)]
        (savel-label-and-load-next! lbl)))
    true))

(defn listen-to-keyboard!
  []
  (let [node (first
               (.getElementsByTagName js/document "body"))]
    (.addEventListener node "keypress"
      on-key!)))


(rum/defc <welcome> < rum.core/reactive
  []
  (let [state (rum/react a-state)]
    [:div {:class "container"}
     [:h1 "Labelling praising comments"]
     [:div
      [:button
       (-> {:on-click (fn [_] (return-to-previous-comment!))
            :class "btn btn-default"}
         (cond->
           (nil? (:previous-comment state))
           (assoc :disabled true)))
       "Previous"]]
     [:br] [:br]
     (<comment-to-label>
       (-> state :comment-to-label))]))

(defn ^:dev/after-load mount-ui! []
  ;; conditionally start the app based on whether the #main-app-area
  ;; node is on the page
  (when-let [node (.getElementById js/document "main-app-area")]
    (rum/mount
      (<welcome>)
      node)))

(defn init! []
  (savel-label-and-load-next! nil)
  (listen-to-keyboard!)
  (mount-ui!))
