import {APICallState, useCloudAPI} from "Authentication/DataHook";
import {MainContainer} from "MainContainer/MainContainer";
import * as Heading from "ui-components/Heading";
import * as React from "react";
import {useEffect, useState} from "react";
import {Box, Card, Flex, Icon, Text, theme} from "ui-components";
import {connect} from "react-redux";
import {Dispatch} from "redux";
import {setRefreshFunction} from "Navigation/Redux/HeaderActions";
import {loadingAction} from "Loading";
import {dispatchSetProjectAction} from "Project/Redux";
import {Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis} from "recharts";
import Table, {TableCell, TableHeader, TableHeaderCell, TableRow} from "ui-components/Table";
import {
    ProductArea, productAreaTitle,
    retrieveBalance,
    RetrieveBalanceResponse,
    transformUsageChartForCharting,
    usage,
    UsageResponse
} from "Accounting/Compute";
import {useProjectManagementStatus} from "Project";
import {ProjectBreadcrumbs} from "Project/Breadcrumbs";
import styled from "styled-components";
import {Dictionary} from "Types";
import {ThemeColor} from "ui-components/theme";
import {Toggle} from "ui-components/Toggle";
import ClickableDropdown from "ui-components/ClickableDropdown";

function dateFormatter(timestamp: number): string {
    const date = new Date(timestamp);
    return `${date.getDate()}/${date.getMonth() + 1} ` +
        `${date.getHours().toString().padStart(2, "0")}:` +
        `${date.getMinutes().toString().padStart(2, "0")}:` +
        `${date.getSeconds().toString().padStart(2, "0")}`;
}

function creditFormatter(credits: number): string {
    let s = credits.toString();
    const a = s.substr(0, s.length - 4);

    let before = a.substr(0, a.length - 2);
    let after = a.substr(a.length - 2);
    if (before === "") before = "0";
    if (after === "") after = "0";
    after = after.padStart(2, "0");

    let beforeFormatted = "";
    {
        const chunksInTotal = Math.ceil(before.length / 3);
        let offset = 0;
        for (let i = 0; i < chunksInTotal; i++) {
            if (i === 0) {
                let firstChunkSize = before.length % 3;
                if (firstChunkSize === 0) firstChunkSize = 3;
                beforeFormatted += before.substr(0, firstChunkSize);
                offset += firstChunkSize;
            } else {
                beforeFormatted += '.';
                beforeFormatted += before.substr(offset, 3);
                offset += 3;
            }
        }
    }

    return `${beforeFormatted},${after} DKK`;
}

interface Duration {
    text: string;
    bucketSize: number;
    bucketSizeText: string;
    timeInPast: number;
}

const durationOptions: Duration[] = [
    {
        text: "Today",
        bucketSize: 1000 * 60 * 60,
        bucketSizeText: "every hour",
        timeInPast: 1000 * 60 * 60 * 24
    },
    {
        text: "Past week",
        bucketSize: 1000 * 60 * 60 * 12,
        bucketSizeText: "every 12 hours",
        timeInPast: 1000 * 60 * 60 * 24 * 7
    },
    {
        text: "Past 14 days",
        bucketSize: 1000 * 60 * 60 * 24,
        bucketSizeText: "every day",
        timeInPast: 1000 * 60 * 60 * 24 * 7
    },
    {
        text: "Past 30 days",
        bucketSize: 1000 * 60 * 60 * 24 * 2,
        bucketSizeText: "every other day",
        timeInPast: 1000 * 60 * 60 * 24 * 30
    },
    {
        text: "Past 180 days",
        bucketSize: 1000 * 60 * 60 * 24 * 14,
        bucketSizeText: "every other week",
        timeInPast: 1000 * 60 * 60 * 24 * 180
    },
    {
        text: "Past 365 days",
        bucketSize: 1000 * 60 * 60 * 24 * 30,
        bucketSizeText: "every 30 days",
        timeInPast: 1000 * 60 * 60 * 24 * 365
    },
];

const ProjectUsage: React.FunctionComponent<ProjectUsageOperations> = props => {
    const {projectId, ...projectManagement} = useProjectManagementStatus();

    const [durationOption, setDurationOption] = useState<Duration>(durationOptions[3])

    const now = new Date().getTime();
    const [balance, fetchBalance, balanceParams] = useCloudAPI<RetrieveBalanceResponse>(
        retrieveBalance({includeChildren: true}),
        {wallets: []}
    );

    const [usageResponse, setUsageParams, usageParams] = useCloudAPI<UsageResponse>(
        usage({
            bucketSize: durationOption.bucketSize,
            periodStart: now - durationOption.timeInPast,
            periodEnd: now
        }),
        {charts: []}
    );

    useEffect(() => {
        setUsageParams(usage({
            bucketSize: durationOption.bucketSize,
            periodStart: now - durationOption.timeInPast,
            periodEnd: now
        }));
    }, [durationOption]);

    useEffect(() => {
        props.setRefresh(() => {
            projectManagement.reload();
            setUsageParams({...usageParams, reloadId: Math.random()});
            fetchBalance({...balanceParams, reloadId: Math.random()});
        });
        return () => props.setRefresh();
    }, [projectManagement.reload]);

    return (
        <MainContainer
            header={
                <Flex>
                    <ProjectBreadcrumbs crumbs={[{title: "Usage"}]}/>
                    <ClickableDropdown
                        trigger={<Heading.h4>{durationOption.text} <Icon name={"chevronDown"} size={16} /></Heading.h4>}
                        onChange={opt => setDurationOption(durationOptions[parseInt(opt)])}
                        options={durationOptions.map((it, idx) => {
                            return {text: it.text, value: `${idx}`};
                        })}
                    />
                </Flex>
            }
            sidebar={null}
            main={(
                <>
                    <VisualizationForArea
                        area={ProductArea.COMPUTE}
                        projectId={projectId}
                        usageResponse={usageResponse}
                        durationOption={durationOption}
                        balance={balance}/>
                    <VisualizationForArea
                        area={ProductArea.STORAGE}
                        projectId={projectId}
                        usageResponse={usageResponse}
                        durationOption={durationOption}
                        balance={balance}/>
                </>
            )}
        />
    );
};

const VisualizationForArea: React.FunctionComponent<{
    area: ProductArea,
    projectId: string,
    usageResponse: APICallState<UsageResponse>,
    balance: APICallState<RetrieveBalanceResponse>,
    durationOption: Duration
}> = ({area, projectId, usageResponse, balance, durationOption}) => {
    const charts = usageResponse.data.charts.map(it => transformUsageChartForCharting(it, area));

    const remainingBalance = balance.data.wallets.reduce((sum, wallet) => {
        if (wallet.area === area && wallet.wallet.id === projectId) return sum + wallet.balance;
        else return sum;
    }, 0);

    const balanceAllocatedToChildren = balance.data.wallets.reduce((sum, wallet) => {
        if (wallet.area === area && wallet.wallet.id !== projectId) return sum + wallet.balance;
        else return sum;
    }, 0);

    // provider -> lineName -> usage
    const creditsUsedByWallet: Dictionary<Dictionary<number>> = {};
    let creditsUsedInPeriod = 0;

    for (const chart of charts) {
        const usageByCurrentProvider: Dictionary<number> = {};
        creditsUsedByWallet[chart.provider] = usageByCurrentProvider;

        for (let i = 0; i < chart.points.length; i++) {
            let point = chart.points[i];
            for (const category of Object.keys(point)) {
                if (category === "time") continue;

                const currentUsage = usageByCurrentProvider[category] ?? 0;
                usageByCurrentProvider[category] = currentUsage + point[category];
                creditsUsedInPeriod += point[category];
            }
        }
    }

    return (
        <Box>
            <SummaryCard
                title={productAreaTitle(area)}
                balance={remainingBalance}
                creditsUsed={creditsUsedInPeriod}
                allocatedToChildren={balanceAllocatedToChildren}
            />

            <Box m={35}>
                {charts.map(chart => (
                    <React.Fragment key={chart.provider}>
                        {chart.lineNames.length === 0 ? null : (
                            <>
                                <Heading.h5>Usage {durationOption.bucketSizeText} for {durationOption.text.toLowerCase()} (Provider: {chart.provider})</Heading.h5>
                                <Box mt={20} mb={20}>
                                    <ResponsiveContainer width="100%" height={200}>
                                        <BarChart
                                            syncId="someId"
                                            data={chart.points}
                                            margin={{
                                                top: 10, right: 30, left: 0, bottom: 0,
                                            }}
                                        >
                                            <CartesianGrid strokeDasharray="3 3"/>
                                            <XAxis dataKey="time" tickFormatter={dateFormatter}/>
                                            <YAxis width={150} tickFormatter={creditFormatter}/>
                                            <Tooltip labelFormatter={dateFormatter} formatter={creditFormatter}/>
                                            {chart.lineNames.map((id, idx) => (
                                                <Bar
                                                    key={id}
                                                    dataKey={id}
                                                    fill={theme.chartColors[idx]}
                                                    barSize={24}
                                                />
                                            ))}
                                        </BarChart>
                                    </ResponsiveContainer>
                                </Box>

                                <Box mb={40}>
                                    <Table>
                                        <TableHeader>
                                            <TableRow>
                                                <TableHeaderCell width={30}/>
                                                <TableHeaderCell/>
                                                <TableHeaderCell textAlign="right">Credits Used In
                                                    Period</TableHeaderCell>
                                                <TableHeaderCell textAlign="right">Remaining</TableHeaderCell>
                                                <TableHeaderCell textAlign={"right"}>Include In Chart</TableHeaderCell>
                                            </TableRow>
                                        </TableHeader>
                                        <tbody>
                                        {chart.lineNames.map((p, idx) => (
                                            <TableRow key={p}>
                                                <TableCell>
                                                    <Box width={20} height={20}
                                                         backgroundColor={theme.chartColors[idx]}/>
                                                </TableCell>
                                                <TableCell>{p}</TableCell>
                                                <TableCell textAlign="right">
                                                    {creditFormatter(creditsUsedByWallet[chart.provider]![p]!)}
                                                </TableCell>
                                                <TableCell textAlign="right">
                                                    {creditFormatter(
                                                        balance.data.wallets.find(it =>
                                                            it.wallet.id === chart.lineNameToWallet[p].id &&
                                                            it.wallet.paysFor.provider === chart.lineNameToWallet[p].paysFor.provider &&
                                                            it.wallet.paysFor.id === chart.lineNameToWallet[p].paysFor.id
                                                        )?.balance ?? 0
                                                    )}
                                                </TableCell>
                                                <TableCell textAlign={"right"}>
                                                    <Toggle
                                                        onChange={() => 42}
                                                        scale={1.5}
                                                        activeColor={"green"}
                                                    />
                                                </TableCell>
                                            </TableRow>
                                        ))}
                                        </tbody>
                                    </Table>
                                </Box>
                            </>
                        )}
                    </React.Fragment>
                ))}

            </Box>
        </Box>
    );
};


const SummaryStat = styled.figure`
    flex-grow: 1;
    text-align: center;
    margin: 0;
    
    figcaption {
        display: block;
        color: var(--gray, #ff0);
        text-transform: uppercase;
        font-size: 12px;
    }
`;

const SummaryWrapper = styled(Card)`
    display: flex;
    padding: 15px;
    margin: 0 15px;
    align-items: center;
    
    h4 {
        flex-grow: 2;
    }
`;

const PercentageDisplay: React.FunctionComponent<{
    numerator: number,
    denominator: number,
    // Note this must be sorted ascending by breakpoint
    colorRanges: { breakpoint: number, color: ThemeColor }[]
}> = props => {
    if (props.denominator === 0) {
        return null;
    }

    const percentage = (props.numerator / props.denominator) * 100;
    let color: ThemeColor = "black";
    for (const cRange of props.colorRanges) {
        if (percentage >= cRange.breakpoint) {
            color = cRange.color;
        }
    }

    return <Text as={"span"} color={theme.colors[color]}>({percentage.toFixed(2)}%)</Text>;
};

const SummaryCard: React.FunctionComponent<{
    title: string,
    creditsUsed: number,
    balance: number,
    allocatedToChildren: number
}> = props => {
    return <SummaryWrapper>
        <Heading.h4>{props.title}</Heading.h4>

        <SummaryStat>
            {creditFormatter(props.creditsUsed)}
            <figcaption>Credits used in period</figcaption>
        </SummaryStat>
        <SummaryStat>
            {creditFormatter(props.balance)}
            <figcaption>Credits remaining</figcaption>
        </SummaryStat>
        <SummaryStat>
            {creditFormatter(props.allocatedToChildren)}{" "}
            <PercentageDisplay
                numerator={props.allocatedToChildren}
                denominator={props.balance}
                colorRanges={[
                    {breakpoint: 80, color: "green"},
                    {breakpoint: 100, color: "yellow"},
                    {breakpoint: 175, color: "red"}
                ]}
            />
            <figcaption>Allocated to subprojects</figcaption>
        </SummaryStat>
    </SummaryWrapper>;
};

interface ProjectUsageOperations {
    setRefresh: (refresh?: () => void) => void;
    setLoading: (loading: boolean) => void;
    setActiveProject: (project: string) => void;
}

const mapDispatchToProps = (dispatch: Dispatch): ProjectUsageOperations => ({
    setRefresh: refresh => dispatch(setRefreshFunction(refresh)),
    setLoading: loading => dispatch(loadingAction(loading)),
    setActiveProject: project => dispatchSetProjectAction(dispatch, project),
});

export default connect(null, mapDispatchToProps)(ProjectUsage);
