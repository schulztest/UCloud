import * as React from "react";
import HighlightedCard from "@/ui-components/HighlightedCard";
import {Text, Button, Icon, List, Link} from "@/ui-components";
import * as Heading from "@/ui-components/Heading";
import {ListRow} from "@/ui-components/List";
import {apiUpdate, useCloudCommand} from "@/Authentication/DataHook";
import {EventHandler, MouseEvent, useCallback, useEffect} from "react";
import {doNothing} from "@/UtilityFunctions";
import {useNavigate} from "react-router";
import {ProviderLogo} from "@/Providers/ProviderLogo";
import {ProviderTitle} from "@/Providers/ProviderTitle";
import {Feature, hasFeature} from "@/Features";
import MainContainer from "@/MainContainer/MainContainer";
import {useTitle} from "@/Navigation/Redux/StatusActions";
import {Operations} from "@/ui-components/Operation";
import Spinner from "@/LoadingIcon/LoadingIcon";
import { connectionState } from "./ConnectionState";
import { useUState } from "@/Utilities/UState";

export const Connect: React.FunctionComponent<{ embedded?: boolean }> = props => {
    if (!hasFeature(Feature.PROVIDER_CONNECTION)) return null;

    const state = useUState(connectionState);

    const [, invokeCommand] = useCloudCommand();
    const reload = useCallback(() => {
        state.fetchFresh();
    }, []);

    useEffect(reload, [reload]);

    const providers = state.providers;
    const shouldConnect = providers.some(it => state.canConnectToProvider(it.providerTitle));

    const body = <>
        {!shouldConnect ? null :
            <Text color={"gray"} mb={8}>
                <Icon name={"warning"} color={"orange"} mr={"8px"}/>
                Connect with the services below to use their resources
            </Text>
        }
        <List>
            {providers.map(it => {
                const canConnect = state.canConnectToProvider(it.providerTitle);

                const openFn: React.MutableRefObject<(left: number, top: number) => void> = {current: doNothing};
                const onContextMenu: EventHandler<MouseEvent<never>> = e => {
                    e.stopPropagation();
                    e.preventDefault();
                    openFn.current(e.clientX, e.clientY);
                };

                return (
                    <ListRow
                        onContextMenu={onContextMenu}
                        key={it.provider}
                        icon={<ProviderLogo providerId={it.providerTitle} size={32}/>}
                        left={<ProviderTitle providerId={it.providerTitle}/>}
                        right={!canConnect ?
                            <>
                                <Icon name={"check"} color={"green"}/>
                                <Operations
                                    location={"IN_ROW"}
                                    operations={[
                                        {
                                            confirm: true,
                                            color: "red",
                                            text: "Unlink",
                                            icon: "close",
                                            enabled: () => {
                                                // TODO(Dan): Generalize this for more providers
                                                return it.providerTitle !== "ucloud" && it.providerTitle !== "aau";
                                            },
                                            onClick: async () => {
                                                await invokeCommand(
                                                    apiUpdate(
                                                        { provider: it.providerTitle },
                                                        "/api/providers/integration",
                                                        "clearConnection"
                                                    )
                                                );

                                                reload();
                                            }
                                        }
                                    ]}
                                    selected={[]}
                                    extra={null}
                                    entityNameSingular={"Provider"}
                                    row={it}
                                    openFnRef={openFn}
                                    forceEvaluationOnOpen
                                />
                            </> :
                            <Button
                                height={40}
                                onClick={() => state.connectToProvider(it.providerTitle)}
                                disabled={state.loading}
                            >
                                {state.loading ?
                                    <div style={{marginTop: "-8px"}}>
                                        <Spinner size={16} />
                                    </div> :
                                    "Connect"
                                }
                            </Button>
                        }
                    />
                );
            })}
        </List>
    </>;

    if (props.embedded) {
        return <HighlightedCard
            color={"darkOrange"}
            icon={"key"}
            title={<Link to={"/providers/connect"}><Heading.h3>Providers</Heading.h3></Link>}
        >
            {body}
        </HighlightedCard>;
    } else {
        // NOTE(Dan): You are not meant to swap the embedded property on a mounted component. We should be fine even
        // though we are breaking rules of hooks.
        useTitle("Connect to Providers");
        return <MainContainer main={body}/>;
    }
};

export default Connect;
