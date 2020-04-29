(ns discussion-gems.feature-engineering.post-to-comment
  (:require [discussion-gems.utils.spark :as uspark]
            [discussion-gems.data-sources :as dgds]
            [sparkling.core :as spark]
            [sparkling.destructuring :as s-de]))

;; ------------------------------------------------------------------------------
;; Missing permalinks

(comment ;; Can we reconstruct comment permalinks from the post's permalink ?


  (def d_comments-missing-permalinks
    (uspark/run-local
      (fn [sc]
        (let [sampled-comments
              (->> (dgds/comments-md-sample-rdd sc)
                (spark/filter
                  (fn [c]
                    (not
                      (or
                        (:permalink c)
                        (-> c :body (= "[deleted]"))))))
                (spark/map
                  (fn [c]
                    (select-keys c [:id :name :link_id])))
                (spark/sample false (/ 1e3 6e4) 8392670)
                (spark/collect)
                shuffle vec)
              subm-ids--bv
              (uspark/broadcast-var sc
                (into #{}
                  (keep :link_id)
                  sampled-comments))
              link-id->subm-data
              (->> (dgds/subm-md-sample-rdd sc)
                (spark/filter
                  (fn [s]
                    (let [subm-ids (uspark/broadcast-value subm-ids--bv)]
                      (contains? subm-ids (:name s)))))
                (spark/map
                  (fn [s]
                    [(:name s)
                     (select-keys s [:name :id :permalink])]))
                (spark/collect)
                (into {}))]
          (->> sampled-comments
            (mapv
              (fn [c]
                (merge c
                  (when-some [s (get link-id->subm-data
                                  (:link_id c))]
                    {:comment/submission s})))))))))


  (count @d_comments-missing-permalinks)
  => 804

  (->> @d_comments-missing-permalinks
    (map :comment/submission)
    (map some?)
    frequencies)
  => {true 803, false 1}

  (->> @d_comments-missing-permalinks
    (map #(-> % :comment/submission :permalink some?))
    frequencies)
  => {true 803, false 1}


  (->> @d_comments-missing-permalinks
    shuffle
    (keep #(-> % :comment/submission :permalink))
    (take 10)
    vec)
  =>
  ["/r/france/comments/50lluu/forum_libre_20160901/"
   "/r/france/comments/3b26qt/courtney_love_agressée_par_les_taxis/"
   "/r/france/comments/67fbzp/le_néolibéralisme_est_un_fascisme/"
   "/r/france/comments/6lkzv4/nicolas_hulot_annonce_la_fin_de_la_vente_de/"
   "/r/france/comments/59x9cz/forum_libre_20161029/"
   "/r/france/comments/6yoeqi/après_tesla_nissan_renault_bmw_bientôt_prêt_à/"
   "/r/france/comments/3gtp3i/depuis_cette_nuit_la_terre_vit_sur_ses_réserves/"
   "/r/france/comments/53lu57/forum_libre_20160920/"
   "/r/france/comments/5fzpcd/ma_tête_quand_je_réalise_que_je_nai_plus_de_vrai/"
   "/r/france/comments/5vbg1w/je_ne_peux_plus_aller_au_restaurant_sans_penser_à/"]


  (-> @d_comments-missing-permalinks
    (->>
      (filter :comment/submission)
      shuffle
      (map
        (fn [{:as c, comment-id :id, s :comment/submission}]
          (str
            "https://www.reddit.com"
            (:permalink s)
            comment-id)))
      (take 30)
      vec)
    (doto
      (->> (run! println))))
  =>
  ["https://www.reddit.com/r/france/comments/6uoqlz/quel_est_la_spécificité_de_votre_ville/dlv1hpb"
   "https://www.reddit.com/r/france/comments/3pgnjy/mardi_introspection_que_feriezvous_si_vous_aviez/cw65uri"
   "https://www.reddit.com/r/france/comments/5naued/cest_reparti_comme_en_40/dca5b6c"
   "https://www.reddit.com/r/france/comments/6prch2/des_mecs_tchétchènes_qui_dansent/dks8mog"
   "https://www.reddit.com/r/france/comments/28h66k/45_des_français_prêts_à_voter_fn_daprès_un_sondage/cicfn6a"
   "https://www.reddit.com/r/france/comments/3gtp3i/depuis_cette_nuit_la_terre_vit_sur_ses_réserves/cu1jp3x"
   "https://www.reddit.com/r/france/comments/37aul2/mardi_introspection_quel_élément_de_votre_enfance/crl78ph"
   "https://www.reddit.com/r/france/comments/66jxu2/évitez_les_champs_elysées/dgj1pwh"
   "https://www.reddit.com/r/france/comments/3o5ibq/forum_libre_20151010/cvub97u"
   "https://www.reddit.com/r/france/comments/46e2kt/my_time_in_paris/d050qje"
   "https://www.reddit.com/r/france/comments/2uz7xw/aussi_incroyable_que_cela_puisse_paraître_les_3/cod41gj"
   "https://www.reddit.com/r/france/comments/6lkzv4/nicolas_hulot_annonce_la_fin_de_la_vente_de/djw6g44"
   "https://www.reddit.com/r/france/comments/5nljk4/megathread_premier_débat_de_la_primaire_de_gauche/dccht8w"
   "https://www.reddit.com/r/france/comments/45kfpj/je_cherche_quelquun_qui_à_complètement_changé_de/czyijxg"
   "https://www.reddit.com/r/france/comments/28nufj/match_france_suisse/cicr457"
   "https://www.reddit.com/r/france/comments/3sn39f/xpost_depuis_riamverysmart_que_celui_qui_a_voulu/cwz2vel"
   "https://www.reddit.com/r/france/comments/133xoe/surpris_que_ça_nait_pas_encore_été_posté_ici/c719aj7"
   "https://www.reddit.com/r/france/comments/2vcsij/prison_avec_sursis_pour_labsentéisme_scolaire_de/coh1xu9"
   "https://www.reddit.com/r/france/comments/4ajhx5/vidéo_youtube_usa_vs_france_stéréotypes/d10uip6"
   "https://www.reddit.com/r/france/comments/6xya9v/forum_libre_20170904/dmji828"
   "https://www.reddit.com/r/france/comments/2b9xsy/y_atil_des_mots_que_vous_refusez_demployer/cj3chfy"
   "https://www.reddit.com/r/france/comments/5th6b8/blocage_sur_test_de_qi/ddmt5pw"
   "https://www.reddit.com/r/france/comments/6n4wco/joyeux_14_juillet_à_tous/dk7gr42"
   "https://www.reddit.com/r/france/comments/5q243o/avril_tembouret_sans_valérian_il_y_a_des_éléments/dcw22pu"
   "https://www.reddit.com/r/france/comments/6y6wen/mardi_introspection_quelle_est_votre_meilleure/dml84xd"
   "https://www.reddit.com/r/france/comments/5wmf7k/forum_libre_20170228/debirbd"
   "https://www.reddit.com/r/france/comments/68911l/face_au_péril_fn_lirresponsable_monsieur_macron/dgx14pd"
   "https://www.reddit.com/r/france/comments/5yo23t/programme_macron_un_copiercoller_des/derm91w"
   "https://www.reddit.com/r/france/comments/6escw0/forum_libre_20170602/dics9mf"
   "https://www.reddit.com/r/france/comments/37aul2/mardi_introspection_quel_élément_de_votre_enfance/crl65zr"]

  ;; I tested all 30 of them, they all work.


  *e)


(defn enrich-comments-with-subm-data
  [k extract-subm-data subm-rdd comments-rdd]
  (->> (spark/left-outer-join
         (spark/key-by :link_id comments-rdd)
         (spark/map-values extract-subm-data
           (spark/key-by :name subm-rdd)))
    (spark/map-values
      (fn [c+s]
        (let [c (s-de/key c+s)
              s (s-de/optional-or-nil (s-de/value c+s))]
          (cond-> c
            (some? s) (assoc k s)))))
    (spark/values)))



(defn basic-submission-data
  [s]
  (select-keys s
    [:banned_at_utc
     :created_utc
     :gilded
     :is_self
     :link_flair_text
     :media
     :name
     :num_comments
     :num_crossposts
     :permalink
     :score
     :title
     :url]))


(comment

  (def d_comments-enriched
    (uspark/run-local
      (fn [sc]
        (->>
          (enrich-comments-with-subm-data
            :dgms_comment_submission
            #(basic-submission-data %)
            (dgds/subm-md-sample-rdd sc)
            (dgds/comments-md-sample-rdd sc))
          (spark/sample false (/ 1e3 6e4) 8392670)
          (spark/map #(select-keys % [:name :link_id :dgms_comment_submission]))
          (spark/collect)
          vec))))

  (->> @d_comments-enriched
    shuffle
    (take 10)
    vec)

  (->> @d_comments-enriched
    (keep #(-> % :dgms_comment_submission :media :type))
    frequencies)


  *e)