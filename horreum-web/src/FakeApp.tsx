import React from 'react';
import { useAuth } from 'react-oidc-context';
import {LoginLogout} from "./auth";

function FakeApp() {
    let auth = useAuth()

    // const loginLogout = () => {
    //     if (auth.isAuthenticated) {
    //         return (
    //             <button onClick={() => void auth.removeUser()}>Log Out</button>
    //         )
    //     } else {
    //         return (
    //             <button onClick={() => void auth.signinRedirect()}>Log In</button>
    //         )
    //     }
    // }
    return (
        <div className="App">
            <header className="App-header">
                <LoginLogout/>
            </header>
        </div>
    );
}

export default FakeApp;