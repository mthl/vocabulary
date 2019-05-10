(ns vocabulary.core
  (:require
   [clojure.string :as s]
   [clojure.set :as set]
   #?(:cljs [cljs.env :as env])
   #?(:cljs [cljs.reader :as r])
   #?(:cljs [cljs.analyzer.api :as ana-api])
   ))       

;; #?(:cljs
;; (defn get-snippet-analysis [cljs-code]
;;   (let [empty-compiler-env (ana-api/empty-state)
;;         empty-analyzer-env (ana-api/empty-env)]
;;     (ana-api/in-cljs-user
;;       empty-compiler-env
;;       (ana-api/analyze empty-analyzer-env cljs-code)
;;       empty-compiler-env))))

;; #?(:cljs (def x (get-snippet-analysis ::dummy)))
                        
;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FUN WITH READER MACROS
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cljc-error [msg]
  #?(:clj (Exception. msg)
     :cljs (js/Error msg)))


;; namespace metadata isn't really available at runtime in cljs...
#?(:cljs
   (def cljs-ns-metadata
     "Namespaces in cljs are not proper objects, and there is no metadata
  available at runtime. This atom stores 'pseudo-metadata' declared with
  cljc-put-ns-metadata and accessed with cljc-get-metadata. Clj just uses
  the metadata regime for its ns's"
     (atom {})))

(defn cljc-put-ns-meta!
  "Side-effect: ensures that subsequent calls to (cljc-get-ns-meta `_ns` return `m`
  Where
  <_ns> is an ns(clj only) or the name of a namespace, or 'dummy namespace' whose purpose is to hold
     vocabulary metadata.
  <m> := {<key> <value>, ...}, metadata (clj) or 'pseudo-metadata' (cljs)
  <key> is a keyword containing vocabulary metadata, e.g. ::vann/preferredNamespacePrefix
  NOTE: In cljs, ns's are not available at runtime, so the metadata is stored
    in an atom called 'voc/cljs-ns-metadata'
  Examples of a dummy namespace would be RDF namespaces like vocabulary.rdf,
    vocabulary.foaf, etc.
  "
  ([_ns m]
  #?(:cljs
     (swap! cljs-ns-metadata
            assoc _ns m)
     :clj
     (alter-meta!
      (if (symbol? _ns)
        (or (find-ns _ns)
            (create-ns _ns))
        ;;else not a symbol
        _ns)
      merge m)))
  ([m]
   #?(:cljs (cljc-put-ns-meta! (namespace ::dummy)) 
      :clj (cljc-put-ns-meta! *ns* m))))

(defn cljc-get-ns-meta
  "Returns <metadata> assigned to ns named `_ns`
  Where
  <_ns> names a namespace or a 'dummy' namespace whose sole purpose is to hold metadata.
  <metadata> := {<key> <value>, ...}
  <key> is a keyword containing vocabulary metadata, e.g. :vann/preferredNamespacePrefix
  "
  ([_ns]
   #?(:cljs
      (do
        (assert (symbol? _ns))
        (get @cljs-ns-metadata _ns))
      :clj
      (if (symbol? _ns)
        (if-let [it (find-ns _ns)]
          (meta it))
         ;; else not a symbol
        (meta _ns))))

  ([]
   #?(:cljs (throw (cljc-error "Cannot infer namespace at runtime in cljs"))
      :clj (cljc-get-ns-meta *ns*))))

(defn cljc-find-ns [_ns]
  "Returns <ns name or obj> for <_ns>, or nil.
Where 
<ns name or obj> may either be a namespace (in clj) 
  or the name of a namespace (in cljs)
<_ns> is a symbol which may name a namespace.
NOTE: Implementations involving cljs must use cljs-put/get-ns-meta to declare
  ns metadata.
"
  #?(:clj (find-ns _ns)
     :cljs (@cljs-ns-metadata _ns)
     ))

(defn cljc-all-ns []
  "Returns (<ns name or obj> ...)
Where
<ns name or obj> may either be a namespace (in clj) 
  or the name of a namespace (in cljs)
"
  #?(:clj (all-ns)
     :cljs (keys @cljs-ns-metadata)))

(declare prefix-re-str)
(defn cljc-find-prefixes 
  "Returns #{<prefix>...} for `s`
Where
<prefix> is a prefix found in <s>, for which some (meta ns) has a 
  :vann/preferredNamespacePrefix declaration
<s> is a string, typically a SPARQL query body for which we want to 
  infer prefix declarations.
"
  {:test #(assert
           (= (cljc-find-prefixes (prefix-re-str)
                                  "Select * Where{?s foaf:homepage ?homepage}")
              #{"foaf"}))
   }
  
  [re-str s]
  {:pre [(string? re-str)
         (string? s)]
   }
  #?(:clj
     (let [prefixes (re-matcher (re-pattern re-str) s) 
           ]
       (loop [acc #{}
              next-match (re-find prefixes)]
         (if (not next-match)
           acc
           (let [[_ prefix] next-match]
             (recur (conj acc prefix)
                    (re-find prefixes))))))
     :cljs
     (let [prefix-re (re-pattern (str "^(" re-str ")(.*)"))
           ;; ^(<spaces>(<prefix1>|<prefix2>|...):)(<unparsed>) 
           ]
       (loop [acc #{}
              input s]
         (assert (string? input))
         (if (or (not input) (empty? input))
           acc
           (let [next-match (re-matches prefix-re input)]
             (if next-match
               (let [[_ _ prefix unparsed] next-match]
                 (recur (conj acc prefix)
                        unparsed))
               ;; else there's no match
               ;; TODO: make this less ugly
               ;; Should be OK for shortish strings like SPARQL queries
               ;; for now
               (recur acc (subs input 1)))))))
     ))

;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ;;; NO READER MACROS BEYOND THIS POINT
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ;; SCHEMA

(cljc-put-ns-meta!
 'vocabulary.core
 {:doc "Defines utilities and a set of namespaces for commonly used linked data constructs, metadata of which specifies RDF namespaces, prefixes and other details."
  :vann/preferredNamespacePrefix "voc"
  :vann/preferredNamespaceUri
  "http://rdf.naturallexicon.org/ont-app/vocabulary/"
  })

(def terms
  "Describes T-box for this namespace."
  ^{:triples-format :vector-of-vectors}
  [[:voc/appendix
    :rdf/type :rdf:Property
    :rdfs/comment "<ns> :voc/appendix <triples>
 Asserts that <triples> describe a graph that elaborates on other attributes asserted in usual key-value metadata asserted for <ns>, e.g. asserting a dcat:mediaType relation for some dcat:downloadURL.
 "
    ]
   ])

;; ;; FUNCTIONS
(defn collect-prefixes 
  "Returns {<prefix> <namespace> ...} s.t. <next-ns> is included
Where
<prefix> is a prefix declared in the metadata of <next-ns>
<namespace> is a URI namespace declared for <prefix> in metadata of <next-ns>
<next-ns> is typically an element in a reduction sequence of ns's 
"
  {:test #(assert
           (= (collect-prefixes {}
                                (cljc-get-ns-meta 'vocabulary.foaf))
              {"foaf" (cljc-find-ns 'vocabulary.foaf)}))
   }
  [acc next-ns]
  {:pre (map? acc)
   }
  (let [nsm (cljc-get-ns-meta next-ns)
        ]
    (if-let [p (:vann/preferredNamespacePrefix nsm)]
      (if (set? p)
        (reduce (fn [acc v] (assoc acc v next-ns)) acc p)
        (assoc acc p next-ns))
      acc)))


(defn prefix-to-ns []
  "Returns {<prefix> <ns> ...}
Where 
<prefix> is declared in metadata for some <ns> with 
  :vann/preferredNamespacePrefix 
<ns> is an instance of clojure.lang.ns available within the lexical 
  context in which the  call was made.
"
  (reduce collect-prefixes {} (cljc-all-ns)))

(defn ns-to-namespace 
  "Returns <iri> for <ns>
Where
<iri> is an iri declared with :vann/preferredNamespaceUri in the metadata for 
  <ns>, or nil
<ns> is an instance of clojure.lang.Namespace
"
  {:test #(assert
           (= (ns-to-namespace (cljc-find-ns 'vocabulary.foaf))
              "http://xmlns.com/foaf/0.1/"))
   }
  [ns]
  (:vann/preferredNamespaceUri (cljc-get-ns-meta ns)))

(defn namespace-to-ns  []
  "returns {<namespace> <ns> ...} for each ns with :vann/preferredNamespaceUri
declaration
"
  (let [maybe-mapping (fn [_ns]
                        (if-let [namespace (:vann/preferredNamespaceUri
                                            (cljc-get-ns-meta _ns))
                                 ]
                          [namespace _ns]))
        ]
    (into {}
          (filter identity
                  (map maybe-mapping
                       (cljc-all-ns))))))

(defn- prefixed-ns [prefix]
  "Returns nil or the ns whose `prefix` was declared in metadata with :vann/preferredNamespacePrefix
Where
<prefix> is a string, typically parsed from a keyword.
"
  {:pre [(string? prefix)]
   }
  (get (prefix-to-ns) prefix))


(defn iri-for 
  "Returns <iri>  for `kw` based on metadata attached to <ns>
Where
<iri> is of the form <namespace><value>
<kw> is a keyword of the form <prefix>:<value>
<ns> is an instance of clojure.lang.ns
<prefix> is declared with :vann/preferredNamespacePrefix in metadata of <ns>
<namespace> is typically of the form http://...., declared with 
  :vann/preferredNamespaceUri in metadata of <ns>
"
  {:test #(do (assert
               (= (iri-for :foaf/homepage)
                  "http://xmlns.com/foaf/0.1/homepage"))
              (assert
               (= (iri-for ::blah)
                  "http://rdf.naturallexicon.org/ont-app/vocabulary/blah"))
              (assert
               (= (iri-for (keyword "http://blah"))
                  "http://blah")))
   }
  [kw]
   {:pre [(keyword? kw)]
   }
  (if (re-matches #"^:(http|https|file):.*" (str kw))
      ;;(#{"http:" "https:" "file:"} (namespace kw)) ;; uri scheme http://...
    (subs (str kw) 1)
    ;; else not http....
    (if-let [prefix (namespace kw)
             ]
      (let [_ns (or (cljc-find-ns (symbol prefix))
                    (prefixed-ns prefix))]
        (if-not _ns
          (throw (cljc-error (str "No URI declared for prefix '" prefix "'")))
          (str (-> _ns
                   (ns-to-namespace))
               (name kw))))
      (throw (cljc-error (str "Could not find IRI for " kw))))))

(defn ns-to-prefix 
  "Returns the prefix associated with `_ns`
Where
<_ns> is a clojure namespace, which may have :vann/preferredNamespacePrefix
  declaration in its metadata.   
"
  {:test #(assert
           (= (ns-to-prefix (cljc-find-ns 'vocabulary.foaf))
              "foaf"))
   }
  [_ns]
  (:vann/preferredNamespacePrefix (cljc-get-ns-meta _ns)))

(defn qname-for 
  "Returns the 'qname' URI for `kw`, or <...>'d if there is no prefix. Throws an error if the prefix is specified, but can't be mapped to metadata.
Where
  <kw> is a keyword, in a namespace with LOD declarations in its metadata.
"
  {:test #(do
            (assert
             (or (not= *ns* (cljc-find-ns 'vocabulary.core))
                 (= (qname-for ::blah)
                    "voc:blah")))
            (assert
             (= (qname-for :foaf/homepage)
                "foaf:homepage")))
   }
  [kw]
  {:pre [(keyword? kw)
         ]
   }
  (if-let [prefix (namespace kw)
           ]
    (if (re-matches #"^:(http|https|file):.*" (str kw))
      ;; this is a scheme, not a namespace
      (str "<" prefix "/" (subs (str kw) 1) ">")
      ;;else not http://...
      (let [_ns (or (cljc-find-ns (symbol prefix))
                    #_(aliased-ns prefix)
                    (prefixed-ns prefix))
            ]
        (if-not _ns
          (throw (cljc-error (str "Could not resolve prefix " prefix))))
        
        (str (ns-to-prefix _ns)
             ":"
             (name kw))))
    ;; else no namespace
    (str "<" (name kw) ">")))

(defn namespace-re []
  "Returns a regex to recognize substrings matching a URI for an ns 
  declared with LOD metadata. Groups for namespace and value.
"
  (let [namespace< (fn [a b] ;; match longer first
                     (> (count a)
                        (count b)))
        ]
    (re-pattern (str "^("
                     (s/join "|" (sort namespace<
                                       (keys (namespace-to-ns))))
                     ")(.*)"))))

(defn keyword-for 
  "Returns a keyword equivalent of <uri>, properly prefixed if LOD declarations
  exist in some ns in the current lexical environment.
"
  {:test #(assert
           (= (keyword-for "http://xmlns.com/foaf/0.1/homepage")
              :foaf/homepage))
   }
  [uri]
  {:pre [(string? uri)]
   }
  (let [[_ namespace value] (re-matches (namespace-re) uri)
        ]
    (if (not value)
      (keyword uri)
      (if (not namespace)
        (keyword value)
        (keyword (-> namespace
                     ((namespace-to-ns))
                     cljc-get-ns-meta
                     :vann/preferredNamespacePrefix)
                 value
                 )))))

(defn prefix-re-str []
  "Returns a regex string that recognizes prefixes declared in ns metadata with 
  :vann/preferredNamespacePrefix keys. 
NOTE: this is a string because the actual re-pattern will differ per clj/cljs.
"
   (str "[^a-zA-Z]+("
        (s/join "|" (keys (prefix-to-ns)))
        "):"))

(defn sparql-prefixes-for 
  "Returns [<prefix-string>...] for each prefix identified in <sparql-string>
Where
<prefix-string> := PREFIX <prefix>: <namespace>\n
<prefix> is a prefix defined for <namespace> in metadata of some ns with 
  :vann/preferredNamespacePrefix
<namespace> is a namespace defined in the metadata for some ns with 
  :vann/preferredNamespaceUri
"
  {:test #(assert
           (=
            (sparql-prefixes-for
             "Select * Where{?s foaf:homepage ?homepage}")
            (list "PREFIX foaf: <http://xmlns.com/foaf/0.1/>")))
   }
  [sparql-string]
  (let [sparql-prefix-for (fn [prefix]
                            (str "PREFIX "
                                 prefix
                                 ": <"
                                 (ns-to-namespace
                                  ((prefix-to-ns) prefix))
                                 ">"))
        ]
    (map sparql-prefix-for (cljc-find-prefixes (prefix-re-str)
                                               sparql-string))))

(defn prepend-prefix-declarations 
  "Returns <sparql-string>, prepended with appropriate PREFIX decls.
"
  {:test #(assert
           (= (prepend-prefix-declarations
               "Select * Where{?s foaf:homepage ?homepage}")
              "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\nSelect * Where{?s foaf:homepage ?homepage}"))
               
   }
  [sparql-string]
  
  (s/join "\n" (conj (vec (sparql-prefixes-for sparql-string))
                     sparql-string)))


;; ;;; NAMESPACE DECLARATIONS
;; ;;; These are commonly used RDF namespaces.

(cljc-put-ns-meta!
 'vocabulary.rdf-schema
 {
     :dc/title "The RDF Schema vocabulary (RDFS)"
     :vann/preferredNamespaceUri "http://www.w3.org/2000/01/rdf-schema#"
     :vann/preferredNamespacePrefix "rdfs"
     :foaf/homepage "https://www.w3.org/TR/rdf-schema/"
     :dcat/downloadURL "http://www.w3.org/2000/01/rdf-schema#"
     :voc/appendix [["http://www.w3.org/2000/01/rdf-schema#"
                     :dcat/mediaType "text/turtle"]]
  })

(cljc-put-ns-meta!
 'vocabulary.owl  
    {
     :dc/title "The OWL 2 Schema vocabulary (OWL 2)"
     :dc/description
     "This ontology partially describes the built-in classes and
  properties that together form the basis of the RDF/XML syntax of OWL
  2.  The content of this ontology is based on Tables 6.1 and 6.2 in
  Section 6.4 of the OWL 2 RDF-Based Semantics specification,
  available at http://www.w3.org/TR/owl2-rdf-based-semantics/.  Please
  note that those tables do not include the different annotations
  (labels, comments and rdfs:isDefinedBy links) used in this file.
  Also note that the descriptions provided in this ontology do not
  provide a complete and correct formal description of either the syntax
  or the semantics of the introduced terms (please see the OWL 2
  recommendations for the complete and normative specifications).
  Furthermore, the information provided by this ontology may be
  misleading if not used with care. This ontology SHOULD NOT be imported
  into OWL ontologies. Importing this file into an OWL 2 DL ontology
  will cause it to become an OWL 2 Full ontology and may have other,
  unexpected, consequences."
     :vann/preferredNamespaceUri "http://www.w3.org/2002/07/owl#"
     :vann/preferredNamespacePrefix "owl"
     :foaf/homepage "https://www.w3.org/OWL/"
     :dcat/downloadURL "http://www.w3.org/2002/07/owl"
     :voc/appendix [["http://www.w3.org/2002/07/owl"
                     :dcat/mediaType "text/turtle"]]
     }
    )

(cljc-put-ns-meta!
 'vocabulary.vann
    {
     :rdfs/label "VANN"
     :dc/description "A vocabulary for annotating vocabulary descriptions"
     :vann/preferredNamespaceUri "http://purl.org/vocab/vann"
     :vann/peferredNamespacePrefix "vann"
     :foaf/homepage "http://vocab.org/vann/"
     })

(cljc-put-ns-meta!
 'vocabulary.dc
    {
     :dc/title "Dublin Core Metadata Element Set, Version 1.1"
     :vann/preferredNamespaceUri "http://purl.org/dc/elements/1.1/"
     :vann/preferredNamespacePrefix "dc"
     :dcat/downloadURL "http://purl.org/dc/elements/1.1/"
     :voc/appendix [["http://purl.org/dc/elements/1.1/"
                     :dcat/mediaType "text/turtle"]]
     }
    )

(cljc-put-ns-meta!
 'vocabulary.dct
    {
     :dc/title "DCMI Metadata Terms - other"
     :vann/preferredNamespaceUri "http://purl.org/dc/elements/1.1/"
     :vann/preferredNamespacePrefix "dct"
     :dcat/downloadURL "http://purl.org/dc/terms/1.1/"
     :voc/appendix [["http://purl.org/dc/elements/1.1/"
                     :dcat/mediaType "text/turtle"]]
     }
    )

(cljc-put-ns-meta!
 'vocabulary.shacl
    {
     :rdfs/label "W3C Shapes Constraint Language (SHACL) Vocabulary"
     :rdfs/comment
     "This vocabulary defines terms used in SHACL, the W3C Shapes
   Constraint Language."
     :vann/preferredNamespaceUri "http://www.w3.org/ns/shacl#"
     :vann/preferredNamespacePrefix "sh"
     :foaf/homepage "https://www.w3.org/TR/shacl/"
     :dcat/downloadURL "https://www.w3.org/ns/shacl.ttl"
     })


(cljc-put-ns-meta!
 'vocabulary.dcat
    {
     :dc/title "Data Catalog vocabulary"
     :foaf/homepage "https://www.w3.org/TR/vocab-dcat/"
     :dcat/downloadURL "https://www.w3.org/ns/dcat.ttl"
     :vann/preferredNamespacePrefix "dcat"
     :vann/preferredNamespaceUri "http://www.w3.org/ns/dcat#"
     }
    )
   
(cljc-put-ns-meta!
 'vocabulary.foaf
 {
  :dc/title "Friend of a Friend (FOAF) vocabulary"
  :dc/description "The Friend of a Friend (FOAF) RDF vocabulary,
 described using W3C RDF Schema and the Web Ontology Language."
  :vann/preferredNamespaceUri "http://xmlns.com/foaf/0.1/"
  :vann/preferredNamespacePrefix "foaf"
  :foaf/homepage "http://xmlns.com/foaf/spec/"
  :dcat/downloadURL "http://xmlns.com/foaf/spec/index.rdf"
  :voc/appendix [["http://xmlns.com/foaf/spec/index.rdf"
                  :dcat/mediaType "application/rdf+xml"]]
  }
 )

(cljc-put-ns-meta!
 'vocabulary.skos
    {
     :dc/title "SKOS Vocabulary"
     :dc/description "An RDF vocabulary for describing the basic
   structure and content of concept schemes such as thesauri,
   classification schemes, subject heading lists, taxonomies,
   'folksonomies', other types of controlled vocabulary, and also
   concept schemes embedded in glossaries and terminologies."
     :vann/preferredNamespaceUri "http://www.w3.org/2004/02/skos/core#"
     :vann/preferredNamespacePrefix "skos"
     :foaf/homepage "https://www.w3.org/2009/08/skos-reference/skos.html"
     :dcat/downloadURL "https://www.w3.org/2009/08/skos-reference/skos.rdf"
     :voc/appendix [["https://www.w3.org/2009/08/skos-reference/skos.rdf"
                     :dcat/mediaType "application/rdf+xml"]]   
     }
    )


(cljc-put-ns-meta!
 'vocabulary.schema
    {
     :vann/preferredNamespaceUri "http://schema.org/"
     :vann/preferredNamespacePrefix "schema"
     :dc/description "Schema.org is a collaborative, community activity
   with a mission to create, maintain, and promote schemas for
   structured data on the Internet, on web pages, in email messages,
   and beyond. "
     :foaf/homepage "https://schema.org/"
     :dcat/downloadURL #{"http://schema.org/version/latest/schema.ttl"
                         "http://schema.org/version/latest/schema.jsonld"}
     :voc/appendix [["http://schema.org/version/latest/schema.ttl"
                     :dcat/mediaType "text/turtle"]
                    ["http://schema.org/version/latest/schema.jsonld"
                     :dcat/mediaType "application/ld+json"]]
     })

(cljc-put-ns-meta!
 'vocabulary.xsd
    {
     :dc/description "Offers facilities for describing the structure and
   constraining the contents of XML and RDF documents"
     :vann/preferredNamespaceUri "http://www.w3.org/2001/XMLSchema#"
     :vann/preferredNamespacePrefix "xsd"
     :foaf/homepage "https://www.w3.org/2001/XMLSchema"
     })


