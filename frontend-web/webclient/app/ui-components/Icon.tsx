import * as React from 'react'
import styled from 'styled-components'
import { space, color } from 'styled-system'
import { icons } from './icons.json'
import theme from './theme'

const getPath = ({ name }) => icons[name]

const Svg = styled.svg`
  flex: none;
  ${space} ${color};
`

const IconBase = ({ name, size, ...props }): JSX.Element => {
  const icon = getPath({ name })
  if (!icon) return (<></>);

  const listPath = icon.path.map((path: [string, string?], i: number) =>
    //fill can be null, in which case it will not render 
    <path key={i} d={path[0]} fill={path[1]} />
  )

  return (
    <Svg
      {...props}
      viewBox={icon.viewBox}
      width={size}
      height={size}
      fill="currentcolor"
    >
      {listPath}
    </Svg>
  )
}

export interface IconProps {
  name: IconName
  size?: string | number
  color?: string
  cursor?: string
}

const Icon = styled(IconBase) <IconProps>`
  flex: none;
  cursor: ${(props: IconProps) => props.cursor}
  ${space} ${color};
`;

Icon.displayName = "Icon"

Icon.defaultProps = {
  theme,
  cursor: "auto",
  name: "notification",
  size: 24
}

// Use to see every available icon.
export const EveryIcon = () => (
  <>
    {Object.keys(icons).map((it: IconName , i) =>
      (<span><span>{it}</span>: <Icon name={it} key={i} />, </span>)
    )}
  </>
);

type IconName = 
  "copy" |
  "download" |
  "trash" |
  "move" |
  "open" |
  "rename" |
  "uploadFolder" |
  "upload" |
  "delete" |
  "play" |
  "apps" |
  "starRibbon" |
  "starFilled" |
  "star_empty" |
  "activity" |
  "dashboard" |
  "shares" |
  "publish" |
  "admin" |
  "notification" |
  "search" |
  "radioChecked" |
  "radioEmpty" |
  "chevronDown" |
  "boxEmpty" |
  "boxChecked";

export default Icon
