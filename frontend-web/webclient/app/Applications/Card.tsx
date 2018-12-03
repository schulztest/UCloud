import * as React from "react";
import { Link, Image } from "ui-components";
import { Relative, Box, Absolute, Text, Icon } from "ui-components";
import { EllipsedText } from "ui-components/Text";
import { Card } from "ui-components";
import { Application } from ".";
import styled from "styled-components";
import * as ReactMarkdown from "react-markdown";

interface ApplicationCardProps {
    favoriteApp?: (name: string, version: string) => void,
    app: Application,
    isFavorite?: boolean,
    linkToRun?: boolean
}

const AppCardActionsBase = styled.div``;

const AppCardBase = styled(Link)`
    padding: 10px;
    width: 100%;
    display: flex;
    align-items: center;

    & > img {
        width: 32px;
        height: 32px;
        margin-right: 16px;
        border-radius: 5px;
        flex-shrink: 0;
    }

    & > strong {
        margin-right: 16px;
        font-weight: bold;
        flex-shrink: 0;
    }

    & > ${EllipsedText} {
        color: ${(props) => props.theme.colors.gray};
        flex-grow: 1;
    }

    & > ${EllipsedText} > p {
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
    }
`;

export const ApplicationCardContainer = styled.div`
    display: flex;
    flex-direction: column;

    & > ${AppCardBase}:first-child {
        border: 1px solid ${props => props.theme.colors.borderGray};
        border-top-left-radius: 5px;
        border-top-right-radius: 5px;
    }

    & > ${AppCardBase} {
        border: 1px solid ${props => props.theme.colors.borderGray};
        border-top: 0;
    }

    & > ${AppCardBase}:last-child {
        border-bottom-left-radius: 5px;
        border-bottom-right-radius: 5px;
    }
`;

export const SlimApplicationCard: React.StatelessComponent<ApplicationCardProps> = (props) => {
    const appInfo = props.app.description.info;
    return (
        <AppCardBase to={props.linkToRun ? `/applications/${appInfo.name}/${appInfo.version}` : `/applications/details/${appInfo.name}/${appInfo.version}`}>
            <img src={props.app.imageUrl} />
            <strong>{props.app.description.title} v{props.app.description.info.version}</strong>
            <EllipsedText>
                <ReactMarkdown
                    source={props.app.description.description}
                    allowedTypes={["text", "root", "paragraph"]} />
            </EllipsedText>
            <AppCardActionsBase><Icon name="chevronDown" rotation={-90} /></AppCardActionsBase>
        </AppCardBase>
    );
};

export const ApplicationCard = ({ app, favoriteApp, isFavorite, linkToRun }: ApplicationCardProps) => (
    <Link to={linkToRun ?
        `/applications/${app.description.info.name}/${app.description.info.version}` :
        `/applications/details/${app.description.info.name}/${app.description.info.version}`
    }>
        <Card width="250px">

            <Relative height="135px">
                <Box>
                    <Image src={app.imageUrl} />
                    <Absolute top="6px" left="10px">
                        <Text
                            fontSize={2}
                            align="left"
                            color="white"
                        >
                            {app.description.title}
                        </Text>
                    </Absolute>
                    <Absolute top={"26px"} left={"14px"}>
                        <Text fontSize={"xxs-small"} align="left" color="white">
                            v{app.description.info.version}
                        </Text>
                    </Absolute>
                    <Absolute bottom="10px" left="10px">
                        <EllipsedText width={220} title={`by ${app.description.authors.join(", ")}`} color="white">
                            by {app.description.authors.join(", ")}
                        </EllipsedText>
                    </Absolute>
                </Box>
            </Relative>
            <Box m="10px">
                <Text>
                    {app.description.description.slice(0, 100)}
                </Text>
            </Box>
        </Card >
    </Link >
);
