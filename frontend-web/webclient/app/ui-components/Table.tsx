import styled from "styled-components";
import { textAlign, TextAlignProps, WidthProps, width } from "styled-system";
import { HideProps, hidden } from "./Hide";
import theme from "./theme";

export const Table = styled.table`
    width: 100%;
    border: 0px;
    border-spacing: 0;
`;

export const TableBody = styled.tbody``;

export const TableCell = styled.td<TextAlignProps & HideProps & WidthProps>`
    border: 0px;
    border-spacing: 0;
    ${width};
    ${textAlign};
    ${hidden("xs")} ${hidden("sm")} ${hidden("md")} ${hidden("lg")} ${hidden("xl")};
`;

const highlighted = ({ highlighted }: { highlighted?: boolean }) => highlighted ? { backgroundColor: theme.colors.lightBlue } : null;

const contentAlign = props => props.aligned ? { verticalAlign: props.aligned } : null;


export const TableRow = styled.tr<{ highlighted?: boolean, contentAlign?: string, cursor?: string }>`
    ${highlighted};
    ${contentAlign};
    cursor: ${props => props.cursor};

    & > ${TableCell} {
        border-spacing: 0;
        border-top: 1px solid rgba(34,36,38,.1);
        padding-top: 11px;
        padding-bottom: 11px;
    }
`;

TableRow.defaultProps = {
    cursor: "auto"
}

export const TableHeader = styled.thead`
    padding-top: 11px;
    padding-bottom: 11px;
`;

export const TableHeaderCell = styled.th<TextAlignProps & HideProps>`
    border-spacing: 0;
    border: 0px;
    ${textAlign};
    ${hidden("xs")} ${hidden("sm")} ${hidden("md")} ${hidden("lg")} ${hidden("xl")};
`;


export default Table;