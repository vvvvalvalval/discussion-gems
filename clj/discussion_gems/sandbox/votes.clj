(ns discussion-gems.sandbox.votes
  (:require [discussion-gems.utils.spark :as uspark]
            [discussion-gems.data-sources :as dgds]
            [sparkling.core :as spark]
            [oz.core :as oz]
            [clojure.java.io :as io]))

(defn vote-numbers
  [c]
  (mapv c [:score :ups :downs]))

(comment ;; Viewing the distributions of parent-vote / child-vote ratios
  (def d_vote-numbers
    (uspark/run-local
      (fn [sc]
        (->> (spark/union
               (dgds/subm-md-sample-rdd sc)
               (dgds/comments-md-sample-rdd sc))
          (uspark/flow-parent-value-to-children
            map? :name :parent_id
            #(vote-numbers %)
            (fn [child parent-votes]
              (assoc child :parent-vote-numbers parent-votes)))
          (spark/map
            #(assoc % :vote-numbers (vote-numbers %)))
          (spark/map
            #(select-keys %
               [:name
                :link
                :vote-numbers
                :parent-vote-numbers]))
          (spark/collect)))))

  (count @d_vote-numbers)
  => 124586

  (->> @d_vote-numbers
    shuffle (take 20) vec)
  =>
  [{:name "t3_4unq9e", :vote-numbers [2 2 0]}
   {:name "t1_dzy3zvt", :vote-numbers [0 nil nil], :parent-vote-numbers [0 nil nil]}
   {:name "t1_dwn2a8g", :vote-numbers [10 nil nil], :parent-vote-numbers [-8 nil nil]}
   {:name "t1_dup7m4t", :vote-numbers [1 nil nil], :parent-vote-numbers [2 nil nil]}
   {:name "t1_cs0si3n", :vote-numbers [3 3 0], :parent-vote-numbers [7 7 0]}
   {:name "t1_eexw076", :vote-numbers [1 nil nil], :parent-vote-numbers [4 nil nil]}
   {:name "t1_ddqliwb", :vote-numbers [1 nil nil], :parent-vote-numbers [1 nil nil]}
   {:name "t1_dgq0p5x", :vote-numbers [6 nil nil], :parent-vote-numbers [8 nil nil]}
   {:name "t3_a2pvh8", :vote-numbers [1 nil nil]}
   {:name "t1_df2bx27", :vote-numbers [5 nil nil], :parent-vote-numbers [85 nil nil]}
   {:name "t1_dapsnrt", :vote-numbers [1 nil nil], :parent-vote-numbers [1 nil nil]}
   {:name "t1_dgv6gdz", :vote-numbers [6 nil nil], :parent-vote-numbers [15 nil nil]}
   {:name "t1_e3xu0jz", :vote-numbers [3 nil nil], :parent-vote-numbers [-4 nil nil]}
   {:name "t1_eo8q1dw", :vote-numbers [10 nil nil], :parent-vote-numbers [6 nil nil]}
   {:name "t1_dnpr69u", :vote-numbers [1 nil nil], :parent-vote-numbers [1 nil nil]}
   {:name "t1_d8niqs6", :vote-numbers [3 nil nil], :parent-vote-numbers [1 nil nil]}
   {:name "t1_d7a2e21", :vote-numbers [2 2 nil], :parent-vote-numbers [1 1 nil]}
   {:name "t1_ekaw1n5", :vote-numbers [2 nil nil], :parent-vote-numbers [9 nil nil]}
   {:name "t1_dgtgr1p", :vote-numbers [7 nil nil], :parent-vote-numbers [-1 nil nil]}
   {:name "t1_e3rafnd", :vote-numbers [-8 nil nil], :parent-vote-numbers [6 nil nil]}]

  (oz/start-server! 10666)

  ;; from the Oz tutorial
  (defn play-data [& names]
    (for [n names
          i (range 20)]
      {:time i :item n :quantity (+ (Math/pow (* i (count n)) 0.8) (rand-int (count n)))}))

  (def line-plot
    {:data {:values (play-data "monkey" "slipper" "broom")}
     :encoding {:x {:field "time" :type "quantitative"}
                :y {:field "quantity" :type "quantitative"}
                :color {:field "item" :type "nominal"}}
     :mark "line"})

  ;; Render the plot
  (oz/view! line-plot)


  (def stacked-bar
    {:data {:values (play-data "munchkin" "witch" "dog" "lion" "tiger" "bear")}
     :mark "bar"
     :encoding {:x {:field "time"
                    :type "ordinal"}
                :y {:aggregate "sum"
                    :field "quantity"
                    :type "quantitative"}
                :color {:field "item"
                        :type "nominal"}}})

  (oz/view! stacked-bar)

  (def viz
    [:div
     [:h1 "Look ye and behold"]
     [:p "A couple of small charts"]
     [:div {:style {:display "flex" :flex-direction "row"}}
      [:vega-lite line-plot]
      [:vega-lite stacked-bar]]
     [:p "A wider, more expansive chart"]
     [:vega-lite stacked-bar]
     [:h2 "If ever, oh ever a viz there was, the vizard of oz is one because, because, because..."]
     [:p "Because of the wonderful things it does"]])

  (oz/view! viz)


  (do
    (def score-values
      (vec
        (for [c (-> @d_vote-numbers
                  #_(->> shuffle (take 10000)))
              :let [self-score (get-in c [:vote-numbers 0])
                    parent-score (get-in c [:parent-vote-numbers 0])]
              :when (and
                      (some? parent-score)
                      (> parent-score 0))
              :let [ratio (double
                            (/ self-score
                              (+ 10.                        ;; smoothing
                                parent-score)))]]
          {:name (:name c)
           :self_score self-score
           :parent_score parent-score
           :score_ratio ratio})))

    (def score-ratio-plot0
      [:div
       [:vega-lite
        {:data {:values}

         :encoding {:x {:field :parent_score
                        :type "quantitative"
                        :scale {:type :log}}
                    :y {:field :score_ratio
                        :type "quantitative"}}
         :mark "point"}]])
    (oz/view! score-ratio-plot0)

    (def score-ratio-plot1
      [:div
       [:vega
        {:$schema "https://vega.github.io/schema/vega/v5.json",
         :autosize "pad",
         :axes [{:scale "x", :grid true, :orient "bottom", :title "Parent Score", :domain false}
                {:scale "y", :grid true, :orient "left", :title "Smoothed Score Ratio", :titlePadding 5, :domain false}],
         :data [#_
                {:transform [{:y {:expr "scale('y', datum.Miles_per_Gallon)"},
                              :counts {:signal "counts"},
                              :type "kde2d",
                              :size [{:signal "width"} {:signal "height"}],
                              :bandwidth {:signal "[bandwidth, bandwidth]"},
                              :groupby ["Origin"],
                              :x {:expr "scale('x', datum.Horsepower)"}}],
                 :name "density",
                 :source "source"}
                {:transform [{:field "grid", :type "isocontour", :levels 3, :resolve {:signal "resolve"}}],
                 :name "contours",
                 :source "density"}],
         :description "A contour plot example, overlaying a density estimate on scatter plot points.",
         :height 400,
         :legends [{:stroke "color", :symbolType "stroke"}],
         :marks [{:name "marks",
                  :type "symbol",
                  :from {:data "source"},
                  :encode {:update {:y {:scale "y", :field "Miles_per_Gallon"},
                                    :fill {:value "#ccc"},
                                    :size {:value 4},
                                    :x {:scale "x", :field "Horsepower"}}}}
                 {:transform [{:color {:expr "scale('color', datum.datum.Origin)"},
                               :field "datum.grid",
                               :type "heatmap",
                               :resolve {:signal "resolve"}}],
                  :type "image",
                  :from {:data "density"},
                  :encode {:update {:y {:value 0},
                                    :aspect {:value false},
                                    :width {:signal "width"},
                                    :x {:value 0},
                                    :height {:signal "height"}}}}
                 {:clip true,
                  :transform [{:field "datum.contour", :type "geopath"}],
                  :type "path",
                  :from {:data "contours"},
                  :encode {:enter {:strokeOpacity {:value 1},
                                   :stroke {:scale "color", :field "Origin"},
                                   :strokeWidth {:value 1}}}}],
         :padding 5,
         :scales [{:zero true,
                   :name "x",
                   :type "linear",
                   :round true,
                   :nice true,
                   :domain {:field "Horsepower", :data "source"},
                   :range "width"}
                  {:zero true,
                   :name "y",
                   :type "linear",
                   :round true,
                   :nice true,
                   :domain {:field "Miles_per_Gallon", :data "source"},
                   :range "height"}],
         :signals [{:name "bandwidth", :value -1, :bind {:min -1, :max 100, :input "range", :step 1}}
                   {:name "resolve", :value "shared", :bind {:options ["independent" "shared"], :input "select"}}
                   {:name "counts", :value true, :bind {:input "checkbox"}}],
         :width 500}]])
    (oz/view! score-ratio-plot0))

  (def high-ratio-ids
    (->> score-values
      (into #{}
        (comp
          (filter #(-> % :score_ratio (> 1.7)))
          (map :name)))))

  (count high-ratio-ids)
  => 983

  (def d_high-ratio-contents
    (uspark/run-local
      (fn [sc]
        (->>
          (spark/union
            (dgds/subm-md-sample-rdd sc)
            (dgds/comments-md-sample-rdd sc))
          (spark/filter #(contains? high-ratio-ids (:name %)))
          (spark/collect)))))

  (->> @d_high-ratio-contents
    shuffle (take 20)
    (mapv #(select-keys % [:name :dgms_body_raw :score])))
  *e)