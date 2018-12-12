import * as React from "react";
import { Cloud } from "Authentication/SDUCloudObject";
import PromiseKeeper from "PromiseKeeper";
import { successNotification, defaultErrorHandler } from "UtilityFunctions";
import { UserCreationState, UserCreationField } from ".";
import { Flex, Box, Input, Label, LoadingButton } from "ui-components";
import * as Heading from "ui-components/Heading";

class UserCreation extends React.Component<{}, UserCreationState> {
    constructor(props) {
        super(props);
        this.state = this.initialState();
    }

    initialState = (): UserCreationState => ({
        promiseKeeper: new PromiseKeeper(),
        submitted: false,
        username: "",
        password: "",
        repeatedPassword: "",
        usernameError: false,
        passwordError: false
    });

    componentWillUnmount() {
        this.state.promiseKeeper.cancelPromises();
    }

    updateFields(field: UserCreationField, value: string) {
        const state = { ...this.state }
        state[field] = value;
        if (field === "username") state.usernameError = false;
        else if (field === "password" || field === "repeatedPassword") state.passwordError = false;
        this.setState(() => state);
    }

    submit(e: React.SyntheticEvent) {
        e.preventDefault();

        let usernameError = false;
        let passwordError = false;
        const { username, password, repeatedPassword } = this.state;
        if (!username) usernameError = true;
        if (!password || password !== repeatedPassword) passwordError = true;
        this.setState(() => ({ usernameError, passwordError }));
        if (!usernameError && !passwordError) {
            this.state.promiseKeeper.makeCancelable(
                Cloud.post("/auth/users/register", { username, password }, "")
            ).promise.then(f => {
                successNotification(`User '${username}' successfully created`);
                this.setState(() => this.initialState());
            }).catch(error => {
                const status = defaultErrorHandler(error);
                if (status == 400) {
                    this.setState(() => ({ usernameError: true }));
                }
            });
        }
    }

    render() {
        if (!Cloud.userIsAdmin) return null;

        const {
            usernameError,
            passwordError,
            username,
            password,
            repeatedPassword,
            submitted
        } = this.state;

        return (
            <React.StrictMode>
                <Flex alignItems="center" flexDirection="column">
                    <Box width={0.7}>
                        <Heading.h1>User Creation</Heading.h1>
                        <p>Admins can create new users on this page.</p>
                        <form onSubmit={e => this.submit(e)}>
                            <Label mb="1em">
                                Username
                                <Input
                                    value={username}
                                    color={usernameError ? "red" : "gray"}
                                    onChange={({ target: { value } }) => this.updateFields("username", value)}
                                    placeholder="Username..."
                                />
                            </Label>
                            <Label mb="1em">
                                Password
                                <Input
                                    value={password}
                                    type="password"
                                    color={passwordError ? "red" : "gray"}
                                    onChange={({ target: { value } }) => this.updateFields("password", value)}
                                    placeholder="Password..."
                                />
                            </Label>
                            <Label mb="1em">
                                Repeat password
                                <Input
                                    value={repeatedPassword}
                                    type="password"
                                    color={passwordError ? "red" : "gray"}
                                    onChange={({ target: { value } }) => this.updateFields("repeatedPassword", value)}
                                    placeholder="Repeat password..."
                                />
                            </Label>
                            <LoadingButton
                                type="submit"
                                content="Create user"
                                hovercolor="darkGreen"
                                color="green"
                                loading={submitted}
                            />
                        </form>
                    </Box>
                </Flex>
            </React.StrictMode >
        );
    }
}

export default UserCreation;