(ns active.ephemerol.scanner-run
  (:require [active.clojure.record :refer :all]))

(definterface IState
  (position_row [])
  (set_position_row [x])
  (position_column [])
  (set_position_column [x]))

(deftype Position
    [^:volatile-mutable row
     ^:volatile-mutable column]
  Object
  (equals
    [this other]
    (and (instance? Position other)
         (= row (.position_row other))
         (= column (.position_column other))))
  (hashCode
    [this]
    (+ row column))
  IState
  (position_row [_] row)
  (set_position_row [_ x] (set! row x))
  (position_column [_] column)
  (set_position_column [_ x] (set! column x)))

(defn make-position
  [r c]
  (Position. r c))

(defn position-row
  [^Position p]
  (.position_row p))

(defn set-position-row!
  [^Position p r]
  (.set_position_row p r))

(defn position-column
  [^Position p]
  (.position_column p))

(defn set-position-column!
  [^Position p c]
  (.set_position_column p c))

(defn copy-position
  [pos]
  (Position. (position-row pos)
             (position-column pos)))

(defn position=?
  [pos1 pos2]
  (and (= (position-row pos1) (position-row pos2))
       (= (position-column pos1) (position-column pos2))))

(def ^:private linefeed (int \newline))
(def ^:private tab (int \tab))

(defn update-position!
  [pos ch]
  (case ch
    10 ; linefeed
    (do
      (set-position-column! pos 0)
      (set-position-row! pos (+ 1 (position-row pos))))

    9 ; tab
    (let [col (position-column pos)]
      (set-position-column! pos (* 8 (quot (+ 7 col) 8))))

    (set-position-column! pos (+ 1 (position-column pos)))))

(define-record-type ScanError
  (make-scan-error cause)
  scan-error?
  [cause scan-error-cause])

(def stuck-scan-error (make-scan-error :stuck))
(def eof-scan-error (make-scan-error :eof))

(define-record-type Scanner
  (make-scanner automaton bot-state? states final partition-size partition-bits indices encodings eof-action)
  scanner?
  ;; for debugging only; may be nil; not preserved across scanner->expression
  [automaton scanner-automaton
   ;; says whether state #1 is the after-bot state
   bot-state? scanner-bot-state?
   states scanner-states
   ; array of final states, where each entry is either a regular action or a EolAction record
   final scanner-final
   partition-size scanner-partition-size
   partition-bits scanner-partition-bits
   indices scanner-indices
   encodings scanner-encodings
   eof-action scanner-eof-action])

; internal wrapper to mark actions valid only at eol
(define-record-type EolAction
  (make-eol-action at-eol vanilla)
  eol-action?
  [at-eol eol-action-at-eol
   ;; action valid at the same place, without eol
   vanilla eol-action-vanilla])

(defn- new-bindings+map
  [bindings action->name action new-name]
  (if (contains? action->name action)
    [bindings action->name]
    [(conj bindings new-name action)
     (assoc action->name action new-name)]))

(defn- fill-final-expression
  [final final-name]
  (let [size (count final)]
    (loop [bindings []
           action->name {}
           i 0]
      (if (< i size)
        (if-let [thing (aget final i)]
          (if (eol-action? thing)
            (let [[bindings action->name] (new-bindings+map bindings action->name (eol-action-at-eol thing) (symbol (str "eol" i)))
                  [bindings action->name] (new-bindings+map bindings action->name (eol-action-vanilla thing) (symbol (str "vanilla" i)))]
              (recur bindings action->name (+ 1 i)))
            (let [[bindings action->name] (new-bindings+map bindings action->name thing (symbol (str "action" i)))]
              (recur bindings action->name (+ 1 i))))
          (recur bindings action->name (+ 1 i)))
        ;; next up
        (loop [statements []
               i 0]
          (if (< i size)
            (if-let [thing (aget final i)]
              (if (eol-action? thing)
                (recur (conj statements `(aset ~final-name ~i
                                               (make-eol-action ~(get action->name (eol-action-at-eol thing))
                                                                ~(get action->name (eol-action-vanilla thing)))))
                       (+ 1 i))
                (recur (conj statements `(aset ~final-name ~i
                                               ~(get action->name thing)))
                       (+ 1 i)))
              (recur statements (+ 1 i)))
            ;; ... and one
            `(let ~bindings ~@statements)))))))

(defn- encode-int-array
  [ar]
  ;; work around "method size too large"
  `(int-array (read-string ~(str (vec ar)))))

(defn scanner->expression
  [scanner]
  (let [final (scanner-final scanner)]
    `(let [~'final (object-array ~(count final))
           ~'scanner (make-scanner nil
                                 ~(scanner-bot-state? scanner)
                                 ~(encode-int-array (scanner-states scanner))
                                 ~'final
                                 ~(scanner-partition-size scanner)
                                 ~(scanner-partition-bits scanner)
                                 ~(encode-int-array (scanner-indices scanner))
                                 ~(encode-int-array (scanner-encodings scanner))
                                 ~(scanner-eof-action scanner))]
       ~(fill-final-expression final 'final)
       ~'scanner)))

(defn reverse-list->string
  [rlis]
  (let [sb (StringBuilder. (count rlis))]
    (loop [rlis rlis]
      (if (empty? rlis)
        (do
          (.reverse sb)
          (.toString sb))
        (do
          (.appendCodePoint sb (first rlis))
          (recur (rest rlis)))))))

(defn make-scan-one
  [scanner]

  (let [states (scanner-states scanner)
	bot-state? (scanner-bot-state? scanner)
	final (scanner-final scanner)
	partition-size (scanner-partition-size scanner)
	bits (scanner-partition-bits scanner)
	indices (scanner-indices scanner)
	encodings (scanner-encodings scanner)
	eof-action (scanner-eof-action scanner)]

    (let [mask (- (bit-shift-left 1 bits) 1)
	  state-size (+ 1 partition-size)
          scalar-value->class (fn [sc]
                                (aget encodings
                                      (+ (aget indices
                                               (bit-shift-right sc bits))
                                         (bit-and sc mask))))
          state-next (fn [state-index sc]
                       (let [class (scalar-value->class sc)]
                         (if (= class -1)
                           nil
                           (loop [state-index state-index]
                             (let [base (* state-index state-size)
                                   next-index (aget states (+ base class))]
                               (if (= next-index -1)
                                 (let [tunnel-index (aget states (+ base partition-size))]
                                   (if (= tunnel-index -1)
                                     nil
                                     (recur tunnel-index)))
                                 next-index))))))]

      (fn [start-input start-position]
	(let [position (copy-position start-position)] ; updated
	  (loop [state (if (and bot-state?
                                (zero? (position-column position)))
                         1
                         0)
                 ;; to be prepended to port
                 input start-input
                 ;; lexeme read so far
                 rev-lexeme '()
                 ;; these are the values for the last final state
                 last-action nil
                 last-rev-lexeme '()
                 last-input '()
                 last-position nil]
	    ;; (write (list 'loop state input (reverse rev-lexeme) last-action (reverse last-rev-lexeme) last-input)) (newline)
	    (cond
             (not-empty input)
             (let [c (first input)
                   input (rest input)]
               (update-position! position c)
               (if-let [new-state (state-next state c)]
                 ;; successful transition
                 (let [rev-lexeme (cons c rev-lexeme)]
                   (if-let [action (aget final new-state)]
                     ;; final state
                     (if (eol-action? action) ; EOL action
                       (recur new-state input rev-lexeme 
                              (if (or (empty? input)
                                      (= linefeed (first input)))
                                (eol-action-at-eol action)
                                (eol-action-vanilla action))
                              rev-lexeme
                              input (copy-position position))
                       (recur new-state input rev-lexeme action rev-lexeme
                              input (copy-position position)))
                     ;; non-final state
                     (recur new-state input rev-lexeme
                            last-action last-rev-lexeme last-input last-position)))
                 (if last-action
                    
                   ;; stuck
                   (last-action (reverse-list->string last-rev-lexeme)
                                start-position
                                last-input last-position)
                   ;; stuck, no action
                   [stuck-scan-error start-input start-position])))

	     ;; eof
	     last-action
             (last-action (reverse-list->string last-rev-lexeme)
                          start-position
                          last-input last-position)

	     ;; eof at the beginning
	     (empty? rev-lexeme)
             ;; call either the default or user specified eof handler.
             (eof-action "" start-position '() last-position)

             ;; eof, no action
	     :else
	      [eof-scan-error start-input start-position])))))))

(defn scan-to-list
  [scan-one input input-position]
  (loop [rev-result '()
         input input
         input-position input-position]
    (if (empty? input)
	[(reverse rev-result) input input-position]
        (let [[data input input-position] (scan-one input input-position)]
          (cond
           (not data)
           [(reverse rev-result) input input-position]
           (scan-error? data)
           [data input input-position]
           :else
           (recur (cons data rev-result) input input-position))))))

(define-record-type ScanResult
  (make-scan-result data input input-position)
  scan-result?
  [data scan-result-data ; holds the return in the end either scan-error or empty list
   input scan-result-input              ; the rest of input
   input-position scan-result-input-position]) ;the end position

(defn string->list
  [^String str]
  (let [sz (.length str)]
  (loop [i 0
         lis '()]
    (if (< i sz)
      (let [sv (.codePointAt str i)]
        (recur (+ i (Character/charCount sv))
               (cons sv lis)))
      (reverse lis)))))
