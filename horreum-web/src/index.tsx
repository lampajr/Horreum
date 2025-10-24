import React from "react"
import ReactDOM from "react-dom/client"

import App from "./App"
import * as serviceWorker from "./serviceWorker"

import jsonpath from "jsonpath"
import "./index.css"
import FakeApp from "./FakeApp";
import {UserManager, WebStorageStateStore} from "oidc-client-ts";
import {AuthProvider} from "react-oidc-context";

type KeycloakServerConfig = {
    url: string,
    clientId: string,
    realm: string
}

export const fetchAuth = async () => {

    const response = await fetch('/api/config/keycloak')

    if (!response.ok) {
        throw new Error('Failed to fetch app configuration');
    }

    const kc: KeycloakServerConfig = await response.json();

    return new UserManager({
        authority: `${kc.url}/realms/horreum`,
        client_id: kc.clientId,
        redirect_uri: `${window.location.origin}${window.location.pathname}`,
        post_logout_redirect_uri: window.location.origin,
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
        monitorSession: true, // this allows cross tab login/logout detection
    });
}

export const onSigninCallback = () => {
    window.history.replaceState({}, document.title, window.location.pathname);
};

let userManager = await fetchAuth()

const root = ReactDOM.createRoot(document.getElementById("root") as HTMLElement)
root.render(
    <React.StrictMode>
        <AuthProvider userManager={userManager}>
            <App />
        </AuthProvider>
    </React.StrictMode>
)

// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: https://bit.ly/CRA-PWA
serviceWorker.unregister()

//public API for and user Js
// global.Duration = Duration;
// global.DateTime = DateTime;
globalThis.jsonpath = jsonpath
