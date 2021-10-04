import * as React from "react";
import {Eyes as EyeOptions} from "@/UserSettings/AvatarOptions";
import {generateId as uniqueId} from "@/UtilityFunctions";

export default function Eyes(props: {optionValue: EyeOptions}): JSX.Element {
    switch (props.optionValue) {
        case EyeOptions.Close:
            return <Close />;
        case EyeOptions.Cry:
            return <Cry />;
        case EyeOptions.Default:
            return <Default />;
        case EyeOptions.Dizzy:
            return <Dizzy />;
        case EyeOptions.EyeRoll:
            return <EyeRoll />;
        case EyeOptions.Happy:
            return <Happy />;
        case EyeOptions.Hearts:
            return <Hearts />;
        case EyeOptions.Side:
            return <Side />;
        case EyeOptions.Squint:
            return <Squint />;
        case EyeOptions.Surprised:
            return <Surprised />;
        case EyeOptions.Wink:
            return <Wink />;
        case EyeOptions.WinkWacky:
            return <WinkWacky />;
    }
}

class Close extends React.Component {
    static optionValue = 'Close';

    render() {
        return (
            <g
                id='Eyes/Closed-😌'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'>
                <path
                    d='M16.1601674,32.4473116 C18.006676,28.648508 22.1644225,26 26.9975803,26 C31.8136766,26 35.9591217,28.629842 37.8153518,32.4071242 C38.3667605,33.5291977 37.5821037,34.4474817 36.790607,33.7670228 C34.3395063,31.6597833 30.8587163,30.3437884 26.9975803,30.3437884 C23.2572061,30.3437884 19.8737584,31.5787519 17.4375392,33.5716412 C16.5467928,34.3002944 15.6201012,33.5583844 16.1601674,32.4473116 Z'
                    id='Closed-Eye'
                    transform='translate(27.000000, 30.000000) scale(1, -1) translate(-27.000000, -30.000000) '
                />
                <path
                    d='M74.1601674,32.4473116 C76.006676,28.648508 80.1644225,26 84.9975803,26 C89.8136766,26 93.9591217,28.629842 95.8153518,32.4071242 C96.3667605,33.5291977 95.5821037,34.4474817 94.790607,33.7670228 C92.3395063,31.6597833 88.8587163,30.3437884 84.9975803,30.3437884 C81.2572061,30.3437884 77.8737584,31.5787519 75.4375392,33.5716412 C74.5467928,34.3002944 73.6201012,33.5583844 74.1601674,32.4473116 Z'
                    id='Closed-Eye'
                    transform='translate(85.000000, 30.000000) scale(1, -1) translate(-85.000000, -30.000000) '
                />
            </g>
        )
    }
}

class Cry extends React.Component {
    static optionValue = 'Cry';

    render() {
        return (
            <g id='Eyes/Cry-😢' transform='translate(0.000000, 8.000000)'>
                <circle
                    id='Eye'
                    fillOpacity='0.599999964'
                    fill='#000000'
                    fillRule='evenodd'
                    cx='30'
                    cy='22'
                    r='6'
                />
                <path
                    d='M25,27 C25,27 19,34.2706667 19,38.2706667 C19,41.5846667 21.686,44.2706667 25,44.2706667 C28.314,44.2706667 31,41.5846667 31,38.2706667 C31,34.2706667 25,27 25,27 Z'
                    id='Drop'
                    fill='#92D9FF'
                    fillRule='nonzero'
                />
                <circle
                    id='Eye'
                    fillOpacity='0.599999964'
                    fill='#000000'
                    fillRule='evenodd'
                    cx='82'
                    cy='22'
                    r='6'
                />
            </g>
        )
    }
}

class Default extends React.Component {
    static optionValue = 'Default';

    render() {
        return (
            <g
                id='Eyes/Default-😀'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'>
                <circle id='Eye' cx='30' cy='22' r='6' />
                <circle id='Eye' cx='82' cy='22' r='6' />
            </g>
        )
    }
}

class Dizzy extends React.Component {
    static optionValue = 'Dizzy';

    render() {
        return (
            <g
                id='Eyes/X-Dizzy-😵'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'
                fillRule='nonzero'>
                <path
                    d='M29,25.2 L34.5,30.7 C35,31.1 35.7,31.1 36.1,30.7 L37.7,29.1 C38.1,28.6 38.1,27.9 37.7,27.5 L32.2,22 L37.7,16.5 C38.1,16 38.1,15.3 37.7,14.9 L36.1,13.3 C35.6,12.9 34.9,12.9 34.5,13.3 L29,18.8 L23.5,13.3 C23,12.9 22.3,12.9 21.9,13.3 L20.3,14.9 C19.9,15.3 19.9,16 20.3,16.5 L25.8,22 L20.3,27.5 C19.9,28 19.9,28.7 20.3,29.1 L21.9,30.7 C22.4,31.1 23.1,31.1 23.5,30.7 L29,25.2 Z'
                    id='Eye'
                />
                <path
                    d='M83,25.2 L88.5,30.7 C89,31.1 89.7,31.1 90.1,30.7 L91.7,29.1 C92.1,28.6 92.1,27.9 91.7,27.5 L86.2,22 L91.7,16.5 C92.1,16 92.1,15.3 91.7,14.9 L90.1,13.3 C89.6,12.9 88.9,12.9 88.5,13.3 L83,18.8 L77.5,13.3 C77,12.9 76.3,12.9 75.9,13.3 L74.3,14.9 C73.9,15.3 73.9,16 74.3,16.5 L79.8,22 L74.3,27.5 C73.9,28 73.9,28.7 74.3,29.1 L75.9,30.7 C76.4,31.1 77.1,31.1 77.5,30.7 L83,25.2 Z'
                    id='Eye'
                />
            </g>
        )
    }
}

class EyeRoll extends React.Component {
    static optionValue = 'EyeRoll';

    render() {
        return (
            <g id='Eyes/Eye-Roll-🙄' transform='translate(0.000000, 8.000000)'>
                <circle id='Eyeball' fill='#FFFFFF' cx='30' cy='22' r='14' />
                <circle id='The-white-stuff' fill='#FFFFFF' cx='82' cy='22' r='14' />
                <circle
                    id='Eye'
                    fillOpacity='0.699999988'
                    fill='#000000'
                    cx='30'
                    cy='14'
                    r='6'
                />
                <circle
                    id='Eye'
                    fillOpacity='0.699999988'
                    fill='#000000'
                    cx='82'
                    cy='14'
                    r='6'
                />
            </g>
        )
    }
}

class Happy extends React.Component {
    static optionValue = 'Happy';

    render() {
        return (
            <g
                id='Eyes/Happy-😁'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'>
                <path
                    d='M16.1601674,22.4473116 C18.006676,18.648508 22.1644225,16 26.9975803,16 C31.8136766,16 35.9591217,18.629842 37.8153518,22.4071242 C38.3667605,23.5291977 37.5821037,24.4474817 36.790607,23.7670228 C34.3395063,21.6597833 30.8587163,20.3437884 26.9975803,20.3437884 C23.2572061,20.3437884 19.8737584,21.5787519 17.4375392,23.5716412 C16.5467928,24.3002944 15.6201012,23.5583844 16.1601674,22.4473116 Z'
                    id='Squint'
                />
                <path
                    d='M74.1601674,22.4473116 C76.006676,18.648508 80.1644225,16 84.9975803,16 C89.8136766,16 93.9591217,18.629842 95.8153518,22.4071242 C96.3667605,23.5291977 95.5821037,24.4474817 94.790607,23.7670228 C92.3395063,21.6597833 88.8587163,20.3437884 84.9975803,20.3437884 C81.2572061,20.3437884 77.8737584,21.5787519 75.4375392,23.5716412 C74.5467928,24.3002944 73.6201012,23.5583844 74.1601674,22.4473116 Z'
                    id='Squint'
                />
            </g>
        )
    }
}

class Hearts extends React.Component {
    static optionValue = 'Hearts';

    render() {
        return (
            <g
                id='Eyes/Hearts-😍'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.8'
                fillRule='nonzero'
                fill='#FF5353'>
                <path
                    d='M35.9583333,10 C33.4074091,10 30.8837273,11.9797894 29.5,13.8206358 C28.1106364,11.9797894 25.5925909,10 23.0416667,10 C17.5523182,10 14,13.3341032 14,17.6412715 C14,23.3708668 18.4118636,26.771228 23.0416667,30.376724 C24.695,31.6133636 27.8223436,34.7777086 28.2083333,35.470905 C28.5943231,36.1641015 30.3143077,36.1885229 30.7916667,35.470905 C31.2690257,34.7532872 34.3021818,31.6133636 35.9583333,30.376724 C40.5853182,26.771228 45,23.3708668 45,17.6412715 C45,13.3341032 41.4476818,10 35.9583333,10 Z'
                    id='Heart'
                />
                <path
                    d='M88.9583333,10 C86.4074091,10 83.8837273,11.9797894 82.5,13.8206358 C81.1106364,11.9797894 78.5925909,10 76.0416667,10 C70.5523182,10 67,13.3341032 67,17.6412715 C67,23.3708668 71.4118636,26.771228 76.0416667,30.376724 C77.695,31.6133636 80.8223436,34.7777086 81.2083333,35.470905 C81.5943231,36.1641015 83.3143077,36.1885229 83.7916667,35.470905 C84.2690257,34.7532872 87.3021818,31.6133636 88.9583333,30.376724 C93.5853182,26.771228 98,23.3708668 98,17.6412715 C98,13.3341032 94.4476818,10 88.9583333,10 Z'
                    id='Heart'
                />
            </g>
        )
    }
}



class Side extends React.Component {
    static optionValue = 'Side';

    render() {
        return (
            <g
                id='Eyes/Side-😒'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'>
                <path
                    d='M27.2409577,20.3455337 C26.462715,21.3574913 26,22.6247092 26,24 C26,27.3137085 28.6862915,30 32,30 C35.3137085,30 38,27.3137085 38,24 C38,23.7097898 37.9793961,23.4243919 37.9395713,23.1451894 C37.9474218,22.9227843 37.9097825,22.6709538 37.8153518,22.4071242 C37.7703692,22.2814477 37.7221152,22.1572512 37.6706873,22.0345685 C37.3370199,21.0717264 36.7650456,20.2202109 36.0253277,19.550585 C33.898886,17.3173253 30.5064735,16 26.9975803,16 C22.1644225,16 18.006676,18.648508 16.1601674,22.4473116 C15.6201012,23.5583844 16.5467928,24.3002944 17.4375392,23.5716412 C19.8737584,21.5787519 23.2572061,20.3437884 26.9975803,20.3437884 C27.0788767,20.3437884 27.1600045,20.3443718 27.2409577,20.3455337 Z'
                    id='Eye'
                />
                <path
                    d='M85.2409577,20.3455337 C84.462715,21.3574913 84,22.6247092 84,24 C84,27.3137085 86.6862915,30 90,30 C93.3137085,30 96,27.3137085 96,24 C96,23.7097898 95.9793961,23.4243919 95.9395713,23.1451894 C95.9474218,22.9227843 95.9097825,22.6709538 95.8153518,22.4071242 C95.7703692,22.2814477 95.7221152,22.1572512 95.6706873,22.0345685 C95.3370199,21.0717264 94.7650456,20.2202109 94.0253277,19.550585 C91.898886,17.3173253 88.5064735,16 84.9975803,16 C80.1644225,16 76.006676,18.648508 74.1601674,22.4473116 C73.6201012,23.5583844 74.5467928,24.3002944 75.4375392,23.5716412 C77.8737584,21.5787519 81.2572061,20.3437884 84.9975803,20.3437884 C85.0788767,20.3437884 85.1600045,20.3443718 85.2409577,20.3455337 Z'
                    id='Eye'
                />
            </g>
        )
    }
}

class Squint extends React.Component {
    static optionValue = 'Squint';

    private path1 = uniqueId('react-path-');
    private path2 = uniqueId('react-path-');
    private mask1 = uniqueId('react-mask-');
    private mask2 = uniqueId('react-mask-');

    render() {
        const {path1, path2, mask1, mask2} = this;
        return (
            <g id='Eyes/Squint-😊' transform='translate(0.000000, 8.000000)'>
                <defs>
                    <path
                        d='M14,14.0481187 C23.6099827,14.0481187 28,18.4994466 28,11.5617716 C28,4.62409673 21.7319865,0 14,0 C6.2680135,0 0,4.62409673 0,11.5617716 C0,18.4994466 4.39001726,14.0481187 14,14.0481187 Z'
                        id={path1}
                    />
                    <path
                        d='M14,14.0481187 C23.6099827,14.0481187 28,18.4994466 28,11.5617716 C28,4.62409673 21.7319865,0 14,0 C6.2680135,0 0,4.62409673 0,11.5617716 C0,18.4994466 4.39001726,14.0481187 14,14.0481187 Z'
                        id={path2}
                    />
                </defs>
                <g id='Eye' transform='translate(16.000000, 13.000000)'>
                    <mask id={mask1} fill='white'>
                        <use xlinkHref={'#' + path1} />
                    </mask>
                    <use id='The-white-stuff' fill='#FFFFFF' xlinkHref={'#' + path1} />
                    <circle
                        fillOpacity='0.699999988'
                        fill='#000000'
                        mask={`url(#${mask1})`}
                        cx='14'
                        cy='10'
                        r='6'
                    />
                </g>
                <g id='Eye' transform='translate(68.000000, 13.000000)'>
                    <mask id={mask2} fill='white'>
                        <use xlinkHref={'#' + path2} />
                    </mask>
                    <use id='Eyeball-Mask' fill='#FFFFFF' xlinkHref={'#' + path2} />
                    <circle
                        fillOpacity='0.699999988'
                        fill='#000000'
                        mask={`url(#${mask2})`}
                        cx='14'
                        cy='10'
                        r='6'
                    />
                </g>
            </g>
        )
    }
}

class Surprised extends React.Component {
    static optionValue = 'Surprised';

    render() {
        return (
            <g id='Eyes/Surprised-😳' transform='translate(0.000000, 8.000000)'>
                <circle id='The-White-Stuff' fill='#FFFFFF' cx='30' cy='22' r='14' />
                <circle id='Eye-Ball' fill='#FFFFFF' cx='82' cy='22' r='14' />
                <circle
                    id='Eye'
                    fillOpacity='0.699999988'
                    fill='#000000'
                    cx='30'
                    cy='22'
                    r='6'
                />
                <circle
                    id='Eye'
                    fillOpacity='0.699999988'
                    fill='#000000'
                    cx='82'
                    cy='22'
                    r='6'
                />
            </g>
        )
    }
}

class Wink extends React.Component {
    static optionValue = 'Wink';

    render() {
        return (
            <g
                id='Eyes/Wink-😉'
                transform='translate(0.000000, 8.000000)'
                fillOpacity='0.599999964'>
                <circle id='Eye' cx='30' cy='22' r='6' />
                <path
                    d='M70.4123979,24.204889 C72.2589064,20.4060854 76.4166529,17.7575774 81.2498107,17.7575774 C86.065907,17.7575774 90.2113521,20.3874194 92.0675822,24.1647016 C92.618991,25.2867751 91.8343342,26.2050591 91.0428374,25.5246002 C88.5917368,23.4173607 85.1109468,22.1013658 81.2498107,22.1013658 C77.5094365,22.1013658 74.1259889,23.3363293 71.6897696,25.3292186 C70.7990233,26.0578718 69.8723316,25.3159619 70.4123979,24.204889 Z'
                    id='Winky-Wink'
                    transform='translate(81.252230, 21.757577) rotate(-4.000000) translate(-81.252230, -21.757577) '
                />
            </g>
        )
    }
}

class WinkWacky extends React.Component {
    static optionValue = 'WinkWacky';

    render() {
        return (
            <g id='Eyes/Wink-Wacky-😜' transform='translate(0.000000, 8.000000)'>
                <circle
                    id='Cornea?-I-don&#39;t-know'
                    fill='#FFFFFF'
                    cx='82'
                    cy='22'
                    r='12'
                />
                <circle
                    id='Eye'
                    fillOpacity='0.699999988'
                    fill='#000000'
                    cx='82'
                    cy='22'
                    r='6'
                />
                <path
                    d='M16.1601674,25.4473116 C18.006676,21.648508 22.1644225,19 26.9975803,19 C31.8136766,19 35.9591217,21.629842 37.8153518,25.4071242 C38.3667605,26.5291977 37.5821037,27.4474817 36.790607,26.7670228 C34.3395063,24.6597833 30.8587163,23.3437884 26.9975803,23.3437884 C23.2572061,23.3437884 19.8737584,24.5787519 17.4375392,26.5716412 C16.5467928,27.3002944 15.6201012,26.5583844 16.1601674,25.4473116 Z'
                    id='Winky-Doodle'
                    fillOpacity='0.599999964'
                    fill='#000000'
                />
            </g>
        )
    }
}
