(ns tapalakbot.render-advice
  "Render advice and photo observations separately from original seller facts."
  (:require [clj-harness.telegram :as tg]
            [clojure.string :as str]))

(defn card-notes
  [{:keys [recommendation visual-evidence research-sources]}]
  (str
   (when recommendation
     (str "<p><b>Почему посмотреть:</b> " (tg/escape-html (:reason recommendation)) "</p>"
          (when (seq (:checks recommendation))
            (str "<p><b>Проверить перед покупкой:</b> "
                 (tg/escape-html (str/join " · " (:checks recommendation))) "</p>"))
          (when (seq research-sources)
            (str "<details><summary>Основания из исследования</summary>"
                 (apply str
                        (map (fn [{:keys [title url quote]}]
                               (str "<blockquote>" (tg/escape-html quote) "</blockquote>"
                                    "<p><a href=\"" (tg/escape-html url) "\">"
                                    (tg/escape-html title) "</a></p>")) research-sources))
                 "</details>"))))
   (when (= :ok (:status visual-evidence))
     (str "<details><summary>Что видно на фото — не осмотр</summary>"
          (when (seq (:observations visual-evidence))
            (str "<ul>"
                 (apply str
                        (map (fn [{:keys [text confidence]}]
                               (str "<li>" (when (= :uncertain confidence) "Предположительно: ")
                                    (tg/escape-html text) "</li>"))
                             (:observations visual-evidence))) "</ul>"))
          (when (seq (:limitations visual-evidence))
            (str "<p>" (tg/escape-html (str/join " · " (:limitations visual-evidence))) "</p>"))
          "<p>Исправность и состояние под нагрузкой по фотографии не подтверждены.</p>"
          "</details>"))))
