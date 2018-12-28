import styled from 'styled-components'
import {
  space,
  width,
  height,
  color,
  alignItems,
  justifyContent,
  flexWrap,
  flexDirection,
  SpaceProps, WidthProps, HeightProps, ColorProps, AlignItemsProps,
  JustifyContentProps, FlexWrapProps, FlexDirectionProps
} from 'styled-system'
import theme from "./theme"


export type FlexProps =
  SpaceProps &
  WidthProps &
  HeightProps &
  ColorProps &
  AlignItemsProps &
  JustifyContentProps &
  FlexWrapProps &
  FlexDirectionProps &
  { cursor?: string }


const Flex = styled.div<FlexProps>`
  cursor: ${props => props.cursor};
  display: flex;
  ${space} ${width} ${height} ${color} ${alignItems} ${justifyContent}
  ${flexDirection}
  ${flexWrap}
`

Flex.defaultProps = {
  theme,
  cursor: "auto"
}


Flex.displayName = 'Flex'

export default Flex


/*
import styled from 'styled-components'
import * as StyledSystem from "styled-system";
import theme from "./theme"


interface FlexProps extends StyledSystem.SpaceProps, StyledSystem.WidthProps, StyledSystem.ColorProps,
  StyledSystem.AlignItemsProps, StyledSystem.JustifyContentProps, StyledSystem.FlexWrapProps, StyledSystem.FlexDirectionProps {
    align?: any
    width?: any
    mt?: any
    mr?: any
    ml?: any
    children?: any
    justify?: any
    bg?: any
}


const Flex = styled("div")<FlexProps>`
  display: flex;
  ${StyledSystem.space} ${StyledSystem.width} ${StyledSystem.color} ${StyledSystem.alignItems} ${StyledSystem.justifyContent} ${StyledSystem.flexWrap} ${StyledSystem.flexDirection};
`

Flex.defaultProps = {
  theme
}


Flex.displayName = 'Flex'

export default Flex
*/