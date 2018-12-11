(ns social-wallet-api.test.transactions
  (:require [midje.sweet :refer :all]
            [ring.mock.request :as mock]
            [social-wallet-api.handler :as h]
            [social-wallet-api.test.handler :refer [test-app-name parse-body mongo-db-only]]
            [auxiliary.config :refer [config-read]]
            [taoensso.timbre :as log]
            [cheshire.core :as cheshire]
            [clojure.test.check.generators :as gen]
            [midje.experimental :refer [for-all]]
            [freecoin-lib.core :as lib] 
            [clj-storage.core :as store]
            [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f])
  (:import [org.joda.time DateTimeZone]))

(def Satoshi (BigDecimal. "0.00000001"))
(def int16-fr8 (BigDecimal. "9999999999999999.99999999"))

(def some-from-account "some-from")
(def some-to-account "some-to-account")

(def ISO8601-formatter (f/formatter "yyyy-MM-dd'T'HH:mm:ss.SSS"))
(def UTC-formatter (f/with-zone ISO8601-formatter DateTimeZone/UTC))
(def zulu-formatter (f/formatter "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

(defn new-transaction-request [big-number from-account to-account]
  (h/app
   (->
    (mock/request :post "/wallet/v1/transactions/new")
    (mock/content-type "application/json")
    (mock/body  (cheshire/generate-string (merge mongo-db-only {:from-id from-account
                                                                :to-id to-account
                                                                :amount big-number
                                                                :tags ["blabla"]
                                                                :description "lalala"}))))))

(defn empty-transactions []
  (store/delete-all! (-> @h/connections :mongo :stores-m :transaction-store)))

(against-background [(before :contents (h/init
                                        (config-read test-app-name)
                                        test-app-name))
                     (after :contents (do
                                        (empty-transactions)
                                        (h/destroy)))] 
                    
                    (facts "Create some transactions." :slow
                           (let [sum-to-account (atom (BigDecimal. 0))]
                             (for-all
                              [rand-double (gen/double* {:min Satoshi
                                                         :max int16-fr8
                                                         :NaN? false
                                                         :infinite? false})
                               from-account gen/uuid
                               to-account gen/uuid]
                              {:num-tests 200}
                              (fact "Insert 200 transactions."
                                    (let [amount (.toString rand-double)  
                                          response (new-transaction-request amount
                                                                            from-account
                                                                            to-account)
                                          body (parse-body (:body response))
                                          _ (swap! sum-to-account #(.add % (BigDecimal. amount)))]
                                      (:status response) => 200)))
                               (fact "There are 200 transactions inserted."
                                     (lib/count-transactions (:mongo @h/connections) {}) => 200)
                               (fact "They all have the same tag."
                                     (lib/count-transactions (:mongo @h/connections) {:tags ["blabla"]}) => 200
                                     (lib/count-transactions (:mongo @h/connections) {:tags ["not-there"]}) => 0)
                             (facts "Retrieving transactions limited by pagination."
                                    (fact "Retrieing results without pagination whould default to 10"
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string mongo-db-only))))
                                                body (parse-body (:body response))] 
                                            (count (:transactions body)) => 10
                                            (:total-count body) => 200))
                                    (fact "Retrieving the first 100 transactions"
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only
                                                                                                        {:page 1
                                                                                                         :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count (:transactions body)) => 100
                                            (:total-count body) => 200))
                                    (fact "Retrieving the next 100 transactions."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only
                                                                                                        {:page 2
                                                                                                         :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count (:transactions body)) => 100
                                            (:total-count body) => 200))
                                    (fact "Third page should be empty."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:page 3
                                                                                                                       :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count (:transactions body)) => 0
                                            (:total-count body) => 200))
                                    (fact "Cannot request all 200 in the same time."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:page 1
                                                                                                                       :per-page 200})))))
                                                body (parse-body (:body response))] 
                                            (:error body) => "Cannot request more than 100 transactions."))
                                    (fact "What happens when requesting 0 transactions?"
                                          ;; TODO: this is mongo behaviour to get all transazctions despite using paging. Maybe we want to block this.
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:page 0
                                                                                                                       :per-page 0})))))
                                                body (parse-body (:body response))] 
                                            (count (:transactions body)) => 200
                                            (:total-count body) => 200)))
                             (facts "Retrieving transactions using other identifiers."
                                    (let [latest-transactions (-> (h/app
                                                                   (->
                                                                    (mock/request :post "/wallet/v1/transactions/list")
                                                                    (mock/content-type "application/json")
                                                                    (mock/body  (cheshire/generate-string mongo-db-only))))
                                                                  :body
                                                                  parse-body)
                                          last-transaction (first (:transactions latest-transactions))
                                          last-from-account (:from-id last-transaction)
                                          last-to-account (:to-id last-transaction)]
                                      (fact "Retrieve all transactions from last from account (should be minimum 1)."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (assoc mongo-db-only :account-id last-from-account)))))
                                                body (parse-body (:body response))] 
                                              (>= (count (:transactions body)) 1) => true
                                              (-> body :transactions first :from-id) => last-from-account
                                              (-> body :transactions first :description) => "lalala"))
                                      (fact "Trying to retrieve transactions different than mongo returns an empty collection."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (assoc mongo-db-only :currency "other")))))
                                                body (parse-body (:body response))] 
                                              (count (:transactions body)) => 0
                                              (empty? (:transactions body)) => true))
                                      (fact "Not applicable identifiers to mongo queries are just ignored."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:count 10
                                                                                                                       :from 10})))))
                                                body (parse-body (:body response))] 
                                              (count (:transactions body)) => 10))
                                      (fact "Test whether one can filter by datetime."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:per-page 0 :page 0})))))
                                                body (parse-body (:body response))
                                                transactions (:transactions body)
                                                oldest-timestamp (-> transactions last :timestamp)]
                                            (count transactions) => 200
                                            (Thread/sleep 1000)
                                            (let [response (new-transaction-request "1"
                                                                            "account-1"
                                                                            "account-2")
                                                  newer-timestamp (-> response
                                                                      :body
                                                                      parse-body
                                                                      :timestamp)]
                                              (t/after? (f/parse zulu-formatter newer-timestamp)
                                                        (f/parse zulu-formatter oldest-timestamp)) => true
                                             (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string
                                                                        (merge mongo-db-only
                                                                               {:to-datetime (t/now)
                                                                                :per-page 0
                                                                                :page 0})))))
                                                    body (-> response :body parse-body)]
                                                (:status response) => 200
                                                (-> body :transactions first :timestamp) => newer-timestamp
                                                (-> body :transactions count) => 201)
                                             (let [response (h/app
                                                             (->
                                                              (mock/request :post "/wallet/v1/transactions/list")
                                                              (mock/content-type "application/json")
                                                              (mock/body  (cheshire/generate-string
                                                                           (merge mongo-db-only
                                                                                  {:to-datetime oldest-timestamp
                                                                                   :per-page 0
                                                                                   :page 0})))))
                                                   body (-> response :body parse-body)]
                                               (:status response) => 200
                                               (-> body :transactions count) => 0)

                                             (let [response (h/app
                                                             (->
                                                              (mock/request :post "/wallet/v1/transactions/list")
                                                              (mock/content-type "application/json")
                                                              (mock/body  (cheshire/generate-string
                                                                           (merge mongo-db-only
                                                                                  {:from-datetime newer-timestamp
                                                                                   :per-page 0
                                                                                   :page 0})))))
                                                   body (-> response :body parse-body)]
                                               (:status response) => 200
                                               (-> body :transactions count) => 1)

                                             (let [response (h/app
                                                             (->
                                                              (mock/request :post "/wallet/v1/transactions/list")
                                                              (mock/content-type "application/json")
                                                              (mock/body  (cheshire/generate-string
                                                                           (merge mongo-db-only
                                                                                  {:from-datetime oldest-timestamp
                                                                                   :to-datetime newer-timestamp
                                                                                   :per-page 0
                                                                                   :page 0})))))
                                                   body (-> response :body parse-body)]
                                               (:status response) => 200
                                               (-> body :transactions count) => 200)

                                             (let [response (h/app
                                                             (->
                                                              (mock/request :post "/wallet/v1/transactions/list")
                                                              (mock/content-type "application/json")
                                                              (mock/body  (cheshire/generate-string
                                                                           (merge mongo-db-only
                                                                                  {:description "lalala"})))))
                                                   body (-> response :body parse-body)]
                                               (:status response) => 200
                                               (-> body :transactions count) => 10)))))))))
