(ns platformer.assets
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]))

(defn- write-image!
  [path width height pixels]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)]
    (doseq [[x y color] pixels]
      (.setRGB image x y (.getRGB ^Color color)))
    (ImageIO/write image "png" (File. path))
    path))

(defn write-sprites!
  [directory]
  (.mkdirs (File. directory))
  (let [player (str directory "/player.png")
        coin (str directory "/coin.png")]
    (write-image! player 32 40
                  (for [y (range 40) x (range 32)
                        :let [body? (and (<= 5 x 26) (<= 10 y 38))
                              eye? (and (<= 9 x 12) (<= 14 y 17))
                              eye-two? (and (<= 19 x 22) (<= 14 y 17))]
                        :when (or body? eye? eye-two?)]
                    [x y (cond eye? (Color. 20 28 48)
                               eye-two? (Color. 20 28 48)
                               :else (Color. 70 214 190))]))
    (write-image! coin 24 24
                  (for [y (range 24) x (range 24)
                        :let [distance (Math/sqrt (+ (Math/pow (- x 11.5) 2)
                                                     (Math/pow (- y 11.5) 2)))]
                        :when (<= distance 10)]
                    [x y (if (< distance 6)
                           (Color. 255 239 130)
                           (Color. 255 174 62))]))
    (let [enemy (str directory "/enemy.png")
          projectile (str directory "/projectile.png")]
      (write-image! enemy 32 32
                    (for [y (range 32) x (range 32)
                          :let [body? (and (<= 4 x 27) (<= 8 y 28))
                                eye? (or (and (<= 9 x 12) (<= 12 y 16))
                                         (and (<= 19 x 22) (<= 12 y 16)))]
                          :when (or body? eye?)]
                      [x y (if eye? (Color. 255 239 130) (Color. 178 104 255))]))
      (write-image! projectile 12 12
                    (for [y (range 12) x (range 12)
                          :let [distance (Math/sqrt (+ (Math/pow (- x 5.5) 2)
                                                       (Math/pow (- y 5.5) 2)))]
                          :when (<= distance 5)]
                      [x y (Color. 255 239 130)]))
      {:player player :coin coin :enemy enemy :projectile projectile})))
