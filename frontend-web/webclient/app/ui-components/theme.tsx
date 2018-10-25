const createMediaQuery = n => `@media screen and (min-width:${n})`

const addAliases = (arr, aliases) =>
  aliases.forEach((key, i) =>
    Object.defineProperty(arr, key, {
      enumerable: false,
      get() {
        return this[i]
      }
    })
  )

export const breakpoints = [32, 40, 48, 64];

export const mediaQueries = breakpoints.map(createMediaQuery)

const aliases = ['sm', 'md', 'lg', 'xl']

addAliases(breakpoints, aliases)
addAliases(mediaQueries, aliases)

export const space = [0, 4, 8, 16, 32, 64, 128]

export const font = `'IBM Plex Sans',sans-serif`

export const fontSizes = [12, 14, 16, 20, 24, 32, 40, 56, 72]

export const medium = 300
export const bold = 400
export const regular = medium

// styled-system's `fontWeight` function can hook into the `fontWeights` object
export const fontWeights = {
  medium,
  bold,
  regular
}

export const lineHeights = {
  standard: 1.5,
  display: 1.25
}

const letterSpacings = {
  normal: 'normal',
  caps: '0.025em'
}

export const textStyles = {
  display8: {
    fontSize: fontSizes[8] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display7: {
    fontSize: fontSizes[7] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display6: {
    fontSize: fontSizes[6] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display5: {
    fontSize: fontSizes[5] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display4: {
    fontSize: fontSizes[4] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display3: {
    fontSize: fontSizes[3] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display2: {
    fontSize: fontSizes[2] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display1: {
    fontSize: fontSizes[1] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display
  },
  display0: {
    fontSize: fontSizes[0] + 'px',
    fontWeight: fontWeights.bold,
    lineHeight: lineHeights.display,
    letterSpacing: letterSpacings.caps,
    textTransform: 'uppercase'
  },
  body2: {
    fontSize: fontSizes[2] + 'px',
    fontWeight: fontWeights.medium,
    lineHeight: lineHeights.standard
  },
  body1: {
    fontSize: fontSizes[1] + 'px',
    fontWeight: fontWeights.medium,
    lineHeight: lineHeights.standard
  },
  body0: {
    fontSize: fontSizes[0] + 'px',
    fontWeight: fontWeights.medium,
    lineHeight: lineHeights.standard
  }
}


// color palette
const black = "#000";
const white = "#fff";
const text = "#001833";
const lightBlue = "#cdf";
const blue = "#0055d5"; // primary
const darkBlue = "#049";
const lightGray = "#ebeff3";
const borderGray = "#d1d6db";
const gray = "#c9d3df"; // primary
const midGray = "#53657d";
const darkGray = "#53657d";
const lightGreen = "#cec";
const green = "#0a0"; // secondary
const darkGreen = "#060";
const lightRed = "#fcc";
const red = "#c00"; // secondary
const darkRed = "#800";
const lightOrange = "#feb";
const orange = "#fa0"; // secondary
const darkOrange = "#a50";
const lightPurple = "#ecf";
const purple = "#70b"; // secondary
const darkPurple = "#407";
const lightYellow = "#fedc2a";
const yellow = "#fff3c0";


const colors = {
  black,
  white,
  text,
  blue,
  lightBlue,
  darkBlue,
  gray,
  lightGray,
  midGray,
  borderGray,
  darkGray,
  green,
  lightGreen,
  darkGreen,
  red,
  lightRed,
  darkRed,
  orange,
  darkOrange,
  purple,
  lightPurple,

  // deprecated
  lightOrange,
  darkPurple
}


export { colors }

export const colorStyles = {
  whiteOnText: {
    color: colors.white,
    backgroundColor: colors.text
  },
  whiteOnGray: {
    color: colors.white,
    backgroundColor: colors.gray
  },
  textOnLightGray: {
    color: colors.text,
    backgroundColor: colors.lightGray
  },
  whiteOnBlue: {
    color: colors.white,
    backgroundColor: colors.blue
  },
  blueOnLightBlue: {
    color: colors.blue,
    backgroundColor: colors.lightBlue
  },
  whiteOnGreen: {
    color: colors.white,
    backgroundColor: colors.green
  },
  greenOnLightGreen: {
    color: colors.green,
    backgroundColor: colors.lightGreen
  },
  whiteOnRed: {
    color: colors.white,
    backgroundColor: colors.red
  },
  redOnLightRed: {
    color: colors.red,
    backgroundColor: colors.lightRed
  },
  textOnOrange: {
    color: colors.text,
    backgroundColor: colors.orange
  },
  whiteOnPurple: {
    color: colors.white,
    backgroundColor: colors.purple
  },
  purpleOnLightPurple: {
    color: colors.purple,
    backgroundColor: colors.lightPurple
  },
  textOnWhite: {
    color: colors.text,
    backgroundColor: colors.white
  },
  grayOnWhite: {
    color: colors.gray,
    backgroundColor: colors.white
  },
  blueOnWhite: {
    color: colors.blue,
    backgroundColor: colors.white
  },
  greenOnWhite: {
    color: colors.green,
    backgroundColor: colors.white
  },
  redOnWhite: {
    color: colors.red,
    backgroundColor: colors.white
  },
  purpleOnWhite: {
    color: colors.purple,
    backgroundColor: colors.white
  },
  whiteOnDarkOrange: {
    color: colors.white,
    backgroundColor: colors.darkOrange
  },
  // info: textOnLightGray
  info: {
    color: colors.text,
    backgroundColor: colors.lightGray
  },
  // success: whiteOnGreen
  success: {
    color: colors.white,
    backgroundColor: colors.green
  },
  //warning: textOnOrange
  warning: {
    color: colors.text,
    backgroundColor: colors.orange
  },
  // danger: whiteOnRed
  danger: {
    color: colors.white,
    backgroundColor: colors.red
  }
}

// styled-system's `borderRadius` function can hook into the `radii` object/array
export const radii = [0, 2, 6]
export const radius = '2px'

export const maxContainerWidth = '1280px'

// boxShadows
export const boxShadows = [
  `0 0 2px 0 rgba(0,0,0,.08),0 1px 4px 0 rgba(0,0,0,.16)`,
  `0 0 2px 0 rgba(0,0,0,.08),0 2px 8px 0 rgba(0,0,0,.16)`,
  `0 0 2px 0 rgba(0,0,0,.08),0 4px 16px 0 rgba(0,0,0,.16)`,
  `0 0 2px 0 rgba(0,0,0,.08),0 8px 32px 0 rgba(0,0,0,.16)`
]

// animation duration
export const duration = {
  fast: `150ms`,
  normal: `300ms`,
  slow: `450ms`,
  slowest: `600ms`
}

// animation easing curves
const easeInOut = 'cubic-bezier(0.5, 0, 0.25, 1)'
const easeOut = 'cubic-bezier(0, 0, 0.25, 1)'
const easeIn = 'cubic-bezier(0.5, 0, 1, 1)'

const timingFunctions = {
  easeInOut,
  easeOut,
  easeIn
}

// animation delay
const transitionDelays = {
  small: `60ms`,
  medium: `160ms`,
  large: `260ms`,
  xLarge: `360ms`
}

const theme = {
  breakpoints,
  mediaQueries,
  space,
  font,
  fontSizes,
  fontWeights,
  lineHeights,
  letterSpacings,
  regular,
  bold,
  textStyles,
  colors,
  colorStyles,
  radii,
  radius,
  boxShadows,
  maxContainerWidth,
  duration,
  timingFunctions,
  transitionDelays
}

export default theme
