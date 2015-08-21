(ns freecoin.test.sign-in
  (:require [midje.sweet :refer :all]
            [net.cgrand.enlive-html :as html]
            [ring.mock.request :as rmr]
            [stonecutter-oauth.client :as sc]
            [freecoin.storage :as storage]
            [freecoin.integration.storage-helpers :as sh]
            [freecoin.test.test-helper :as th]
            [freecoin.handlers.sign-in :as fs]))


(def sso-url "SSO_URL")
(def client-id "CLIENT_ID")
(def client-secret "CLIENT_SECRET")
(def callback-uri "CALLBACK_URI")
(def public-key "PUBLICK_KEY") ;; TODO: load this from jwk file

(def test-sso-config (sc/configure sso-url client-id client-secret callback-uri))

(def db-connection (atom nil))

(facts "About signing up via a Stonecutter SSO instance"
       (facts "About the landing page"
              (fact "When not signed in, displays link to sign in with stonecutter"
                    (let [response (fs/landing-page (rmr/request :get "/"))]
                      (:status response) => 200
                      (-> (:body response) (html/html-snippet [:body])) => (th/links-to? [:.clj--sign-in-link] "/sign-in-with-sso")))
              
              (fact "When signed in, displays users balance"))

       (fact "the sign-in endpoint redirects to the stonecutter authorisation url"
             (let [sign-in-handler (fs/sign-in test-sso-config)
                   response (sign-in-handler (rmr/request :get "/sign-in-with-sso"))
                   expected-authorisation-url (str sso-url "/authorisation?"
                                                   "client_id=" client-id
                                                   "&response_type=code"
                                                   "&redirect_uri=" callback-uri)]
               response => (th/check-redirects-to expected-authorisation-url)))

       (facts "About the openid callback endpoint"
              (against-background
               [(before :facts (do (reset! db-connection (storage/connect sh/test-db-config))
                                   (sh/clear-db @db-connection)))
                (after :facts (do (storage/disconnect @db-connection)))])
              
              (facts "When token request yields a valid access_token + id_token"
                     (against-background
                      (sc/request-access-token! ...sso-config... ...auth-code...) => {:access_token ...access-token...
                                                                                      :user-info {:user-id "stonecutter-user-id"
                                                                                                  :email "test@email.com"
                                                                                                  :email_verified true}})
                     (fact "if new user, creates a wallet and redirects to landing page"
                           (let [callback-handler (fs/sso-callback @db-connection ...sso-config...)
                                 response (callback-handler (-> (rmr/request :get "/sso-callback")
                                                                (assoc :params {:code ...auth-code...})))]
                             response => (th/check-redirects-to "/landing-page")
                             response => (th/check-signed-in-as "stonecutter-user-id")
                             (storage/find-by-key @db-connection "wallets" {:email "test@email.com"}) =not=> nil?))
                     
                     (fact "if existing user, retrieves wallet, and redirects to landing page"))
              
              (fact "Redirects to landing page (?) when not accessed as part of a successful openid authentication flow")))
