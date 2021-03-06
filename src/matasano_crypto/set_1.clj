(ns matasano-crypto.set-1
  (:import [org.apache.commons.codec.binary Base64 Hex]
           javax.crypto.spec.SecretKeySpec
           javax.crypto.Cipher)
  ;; http://commons.apache.org/proper/commons-codec/archives/1.10/apidocs/index.html
  (:require [clojure.string :as str]
            [matasano-crypto.set-2 :refer [pkcs7-pad]]))

;;; Challenge 1: hex to base64

;; Remember, bytes are 8 bits (ex: 2r11010011), representing integers between 0
;; and 255. So an array of bytes looks something like [73 39 109 32 107 105].

(defn ->bytes
  "Takes a UTF-8 string and returns a [B of the bytes representing each char."
  [s]
  (.getBytes s "UTF-8"))

(defn decode-hex
  "Takes a string representing a hexadecimal value, where each pair of chars is
  the hex encoding of a character. Returns a [B where each byte is the value of
  that hexadecimal (hex to byte conversion: 0xff -> 255)."
  [s]
  (Hex/decodeHex (char-array s)))

(defn hex->base64
  "Takes a hexadecimal string, decodes it into a [B, then returns that binary
  data encoded with base64.

  Hex pairs, such as 0x4d, are encoded in bytes. Base64 takes 6 bits at a time
  and encodes them as one of 64 different characters (A-Z, a-z, 0-9, +, /)."
  [s]
  (Base64/encodeBase64String (decode-hex s)))


;;; Challenge 2: Fixed XOR

(defn xor
  "Take two equal length arrays of bytes, bit-xors each corresponding byte and
  returns the resulting byte-array"
  [a b]
  (byte-array (map bit-xor a b)))

(defn hex-xor
  "Decodes two hex strings and x-or's them against each other, returning the
  resulting byte-array."
  [a b]
  (let [a-bytes (decode-hex a)
        b-bytes (decode-hex b)]
    (-> (xor a-bytes b-bytes)
        Hex/encodeHexString)))


;;; Challenge 3: Single-byte XOR cipher

(def etaoin-shrdlu "1b37373331363f78151b7f2b783431333d78397828372d363c78373e783a393b3736")

(defn single-byte-xor
  "Given a byte-array a single-byte mask, returns the XOR result."
  [b-array byte]
  (let [mask (byte-array (count b-array) byte)]
    (xor b-array mask)))

(def etaoin-score {\e 0.1202 \t 0.091 \a 0.0812 \o 0.0768 \i 0.0731
                   \n 0.0695 \s 0.0628 \r 0.0602 \h 0.0592 \d 0.0432
                   \l 0.0398 \u 0.0288 \c 0.0271 \m 0.0261 \f 0.023
                   \y 0.0211 \w 0.0209 \g 0.0203 \p 0.0182 \b 0.0149
                   \v 0.0111 \k 0.0069 \x 0.0017 \q 0.0011 \j 0.001
                   \z 0.0007 \space 0.15})

(defn score-text
  "Takes a plaintext string, checks each char against a letter frequency map and
  returns the sum of its scores."
  [s]
  (reduce + (replace {nil -0.01} (map etaoin-score s))))

(defn xor-permutations
  "Takes a byte-array. XOR this byte-array against all single-byte masks, and
  return a map from the mask byte to the result as a string"
  [s]
  (into {} (for [i (range 128)]
             [i (String. (single-byte-xor s (byte i)))])))

(defn most-english-kv
  "Takes a map of masks to strings and returns the kv pair whose v is the most
  english."
  [m]
  (apply max-key (fn [[k v]] (score-text v)) m))

(defn single-byte-xor-cipher [s]
  (val (most-english-kv (xor-permutations (decode-hex s)))))


;;; Challenge 4: Detect single-character XOR

(def c4-data (str/split-lines (slurp "resources/4.txt")))

(defn decrypt-single-byte-xor
  "Takes a vector of strings, one of which has been encrypted with single-byte
  xor. Returns the decrypted message."
  [strings]
  (->> strings
       (map decode-hex)
       (map xor-permutations)
       (map most-english-kv)
       most-english-kv
       val))


;;; Challenge 5: Repeating-key XOR

(defn repeating-key-xor
  [plain-text-bytes key-bytes]
  (let [mask (byte-array (count plain-text-bytes) (cycle key-bytes))]
    (xor plain-text-bytes mask)))


;;; Challenge 6: Break repeating key XOR

(def file6 (str/split-lines (slurp "resources/6.txt")))

(def cipher-bytes-6 (Base64/decodeBase64 (str/join file6)))

(defn hamming-distance
  "Given two byte-arrays, computes their bitwise hamming distance."
  [a b]
  (->> (xor a b)
       (map #(Integer/bitCount %))      ; number of 1s in each byte
       (reduce +)))

(defn- map-keysize->distance
  "Takes a cipher byte-array and returns a map of guessed keysizes to the
  average weighted hamming distances between cipher chunks of that size."
  [cipher]
  (into {} (for [keysize (range 2 40)]
             (let [chunk-pairs (partition 2 1 (take 13 (partition keysize cipher)))
                   normalized-distance (/ (reduce + (map
                                                     #(apply hamming-distance %)
                                                     chunk-pairs))
                                          keysize)]
               [keysize normalized-distance]))))

(defn guess-keysize
  [cipher]
  (key (first (sort-by val < (map-keysize->distance cipher)))))

(defn- transpose-nth
  [n cipher]
  (for [i (range n)]
    (take-nth n (drop i cipher))))

(defn find-key
  "Divides the cipher text into keysize number of blocks, where each block
  corresponds to the same byte of the key. These blocks are solved as single
  byte XORs, the solutions to which can be combined for the cipher text's key."
  [cipher keysize]
  (byte-array
   (for [block (transpose-nth keysize cipher)]
     (->> block
          xor-permutations
          most-english-kv
          key))))

(defn decrypt-vigenere
  [cipher]
  (let [keysize (guess-keysize cipher)
        k (find-key cipher keysize)]
    (String. (repeating-key-xor cipher k))))


;;; Challenge 7: AES in ECB mode

(def file7 (slurp "resources/7.txt"))

(defn aes-ecb-cipher [mode key]
  (let [key-spec (SecretKeySpec. key "AES")
        cipher (Cipher/getInstance "AES/ECB/NoPadding")]
    (.init cipher mode key-spec)
    cipher))

(defn encrypt-aes [bytes key]
  (let [cipher (aes-ecb-cipher Cipher/ENCRYPT_MODE key)
        padded (pkcs7-pad bytes (count key))]
    (.doFinal cipher padded)))

(defn decrypt-aes [bytes key]
  (let [cipher (aes-ecb-cipher Cipher/DECRYPT_MODE key)
        padded (pkcs7-pad bytes (count key))]
    (.doFinal cipher padded)))


;;; Challenge 8: Detect AES in ECB mode

(def file8 (str/split-lines (slurp "resources/8.txt")))

(def file8-ciphers (map ->bytes file8))

(defn has-duplicate-block? [cipher]
  (->> (for [i (range 8 33)]
         (apply distinct? (partition i cipher)))
       (some false?)))

(defn detect-aes-ecb [ciphers]
  (keep-indexed (fn [idx cipher]
                  (when (has-duplicate-block? cipher) [idx cipher]))
                ciphers))
