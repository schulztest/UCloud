import * as React from "react";
import styled from "styled-components";
import Box from "./Box";
import { theme, Flex, Text } from "ui-components";

interface ProgressBaseProps {
    width?: number | string
    height?: number | string
    value?: number | string
    active?: boolean
    label?: string
}

const ProgressBase = styled(Box) <ProgressBaseProps>`
    border-radius: 5px;
    background-color: ${props => props.theme.colors[props.color!]};
    width: ${props => props.width};
    height: ${props => props.height};

    /* From semantic-ui-css */
    ${props => props.active ?
        `animation: progress-active 2s ease infinite;` : null}
    

    @-webkit-keyframes progress-active {
        0% {
            opacity: 0.3;
            width: 0;
        }
        100% {
            opacity: 0;
            width: 100%;
        }
    }

    @keyframes progress-active {
        0% {
            opacity: 0.3;
            width: 0;
        }
        100% {
            opacity: 0;
            width: 100%;
        }
    }
`;

// FIXME: Workaround, not a fix.
// @ts-ignore
ProgressBase.defaultProps = {
    color: "green",
    height: "30px",
    active: false,
    theme
};

const Progress = ({ color, percent, active, label }) => (
    <>
        <ProgressBase height="30px" width="100%" color="lightGray">
            <ProgressBase height="30px" color={color} width={`${percent}% `}>
                {active ? <ProgressBase height="30px" active={true} width="100%" color="black" /> : null}
            </ProgressBase>
        </ProgressBase>
        {label ? <Flex justifyContent="center"><Text>{label}</Text></Flex> : null}
    </>
);

export default Progress;