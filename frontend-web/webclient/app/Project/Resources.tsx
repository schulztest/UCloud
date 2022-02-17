import {Area, AreaChart, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis} from "recharts";
import {MainContainer} from "@/MainContainer/MainContainer";
import * as React from "react";
import {useCallback, useEffect, useMemo, useState} from "react";
import {browseSubAllocations, searchSubAllocations, SubAllocation, useProjectManagementStatus} from "@/Project";
import {ProjectBreadcrumbs} from "@/Project/Breadcrumbs";
import {useLoading, useTitle} from "@/Navigation/Redux/StatusActions";
import {SidebarPages, useSidebarPage} from "@/ui-components/Sidebar";
import {PageV2} from "@/UCloud";
import {DateRangeFilter, EnumFilter, FilterWidgetProps, PillProps, ResourceFilter, ValuePill} from "@/Resource/Filter";
import {capitalized, doNothing, prettierString, timestampUnixMs} from "@/UtilityFunctions";
import {ThemeColor} from "@/ui-components/theme";
import {Box, Flex, Grid, Icon, Link, Text} from "@/ui-components";
import {getCssVar} from "@/Utilities/StyledComponentsUtilities";
import styled from "styled-components";
import {formatDistance} from "date-fns";
import {useCloudAPI} from "@/Authentication/DataHook";
import {emptyPageV2} from "@/DefaultObjects";
import {useRefreshFunction} from "@/Navigation/Redux/HeaderActions";
import {
    browseWallets,
    ChargeType,
    ProductPriceUnit,
    ProductType,
    productTypes,
    productTypeToIcon,
    productTypeToTitle,
    retrieveBreakdown,
    retrieveUsage,
    UsageChart,
    usageExplainer,
    Wallet,
    WalletAllocation,
} from "@/Accounting";
import HighlightedCard from "@/ui-components/HighlightedCard";
import {BrowseType} from "@/Resource/BrowseType";
import {SubAllocationViewer} from "./SubAllocations";
import {Accordion, AccordionWrapper} from "@/ui-components/Accordion";
import {ResourceProgress} from "@/ui-components/ResourcesProgress";
import {format} from "date-fns/esm";
import {Spacer} from "@/ui-components/Spacer";
import {Toggle} from "@/ui-components/Toggle";

const SIMPLE_VIEW = true;

const FORMAT = "dd/MM/yyyy";

function dateFormatter(timestamp: number): string {
    const date = new Date(timestamp);
    return `${date.getDate()}/${date.getMonth() + 1} ` +
        `${date.getHours().toString().padStart(2, "0")}:` +
        `${date.getMinutes().toString().padStart(2, "0")}`;
}

const filterWidgets: React.FunctionComponent<FilterWidgetProps>[] = [];
const filterPills: React.FunctionComponent<PillProps>[] = [];

function registerFilter([w, p]: [React.FunctionComponent<FilterWidgetProps>, React.FunctionComponent<PillProps>]) {
    filterWidgets.push(w);
    filterPills.push(p);
}

registerFilter(DateRangeFilter("calendar", "Usage period", "filterEndDate", "filterStartDate"));
registerFilter(EnumFilter("cubeSolid", "filterType", "Product type", productTypes.map(t => ({
    icon: productTypeToIcon(t),
    title: productTypeToTitle(t),
    value: t
}))));

filterPills.push(props =>
    <ValuePill {...props} propertyName={"filterWorkspace"} secondaryProperties={["filterWorkspaceProject"]}
        showValue={true} icon={"projects"} title={"Workspace"} />);

filterPills.push(props =>
    <ValuePill {...props} propertyName={"filterAllocation"} showValue={false} icon={"grant"} title={"Allocation"} />);

const ResourcesGrid = styled.div`
    display: grid;
    grid-template-columns: 1fr;
    grid-gap: 16px;
`;

const Resources: React.FunctionComponent = () => {
    const managementStatus = useProjectManagementStatus({isRootComponent: true, allowPersonalProject: true});

    const [filters, setFilters] = useState<Record<string, string>>({showSubAllocations: "true"});
    const [usage, fetchUsage] = useCloudAPI<{charts: UsageChart[]}>({noop: true}, {charts: []});
    const [breakdowns, fetchBreakdowns] = useCloudAPI<{charts: BreakdownChart[]}>({noop: true}, {charts: []});
    const [wallets, fetchWallets] = useCloudAPI<PageV2<Wallet>>({noop: true}, emptyPageV2);
    const [allocations, fetchAllocations] = useCloudAPI<PageV2<SubAllocation>>({noop: true}, emptyPageV2);
    const [allocationGeneration, setAllocationGeneration] = useState(0);

    const [maximizedUsage, setMaximizedUsage] = useState<number | null>(null);

    const onUsageMaximize = useCallback((idx: number) => {
        if (maximizedUsage == null) setMaximizedUsage(idx);
        else setMaximizedUsage(null);
    }, [maximizedUsage]);

    const reloadPage = useCallback(() => {
        fetchUsage(retrieveUsage({...filters}));
        fetchBreakdowns(retrieveBreakdown({...filters}));
        fetchWallets(browseWallets({itemsPerPage: 50, ...filters}));
        fetchAllocations(browseSubAllocations({itemsPerPage: 250, ...filters}));
        setAllocationGeneration(prev => prev + 1);
        setMaximizedUsage(null);
    }, [filters]);

    const loadMoreAllocations = useCallback(() => {
        fetchAllocations(browseSubAllocations({itemsPerPage: 250, next: allocations.data.next}));
    }, [allocations.data]);

    const filterByAllocation = useCallback((allocationId: string) => {
        setFilters(prev => ({...prev, "filterAllocation": allocationId}))
    }, [setFilters]);

    const filterByWorkspace = useCallback((workspaceId: string, workspaceIsProject: boolean) => {
        setFilters(prev => ({
            ...prev,
            "filterWorkspace": workspaceId,
            "filterWorkspaceProject": workspaceIsProject.toString()
        }));
    }, [setFilters]);

    const onSubAllocationQuery = useCallback((query: string) => {
        fetchAllocations(searchSubAllocations({query, itemsPerPage: 250}));
    }, []);

    useTitle("Usage");
    useSidebarPage(SidebarPages.Projects);
    useRefreshFunction(reloadPage);
    useEffect(reloadPage, [reloadPage]);
    useLoading(usage.loading || breakdowns.loading || wallets.loading);

    return (
        <MainContainer
            header={<ProjectBreadcrumbs allowPersonalProject crumbs={[{title: "Resources"}]} />}
            headerSize={60}
            sidebar={<>
                <ResourceFilter
                    browseType={BrowseType.MainContent}
                    pills={filterPills}
                    filterWidgets={filterWidgets}
                    sortEntries={[]}
                    properties={filters}
                    setProperties={setFilters}
                    sortDirection={"ascending"}
                    onSortUpdated={doNothing}
                />
            </>}
            main={<ResourcesGrid>
                <Grid gridGap={"16px"}>
                    {maximizedUsage == null ? null : <>
                        <UsageChartViewer maximized c={usage.data.charts[maximizedUsage]}
                            onMaximizeToggle={() => onUsageMaximize(maximizedUsage)} />
                    </>}
                    {maximizedUsage != null ? null :
                        <>
                            <VisualizationSection>
                                {usage.data.charts.map((it, idx) =>
                                    <UsageChartViewer key={idx} c={it} onMaximizeToggle={() => onUsageMaximize(idx)} />
                                )}
                            </VisualizationSection>
                            <VisualizationSection>
                                {breakdowns.data.charts.map((it, idx) =>
                                    <DonutChart key={idx} chart={it} />
                                )}
                            </VisualizationSection>
                            <Wallets wallets={wallets.data.items} />

                            {managementStatus.allowManagement ?
                                <SubAllocationViewer allocations={allocations} generation={allocationGeneration}
                                    loadMore={loadMoreAllocations}
                                    filterByAllocation={filterByAllocation}
                                    filterByWorkspace={filterByWorkspace} wallets={wallets}
                                    onQuery={onSubAllocationQuery} />
                                : null}
                        </>
                    }
                </Grid>
            </ResourcesGrid>}
        />
    );
};

type WalletStore = {
    [key in keyof typeof productTypes]: Wallet[]
};

const VERY_HIGH_DATE_VALUE = 99999999999999;

function resultAsPercent(usage: {balance: number; initialBalance: number}): number {
    return 100 - (usage.balance / usage.initialBalance * 100);
}

function Wallets(props: {wallets: Wallet[]}): JSX.Element | null {
    const [wallets, setWallets] = React.useState<WalletStore>({} as WalletStore);
    const [advancedToggles, setAdvancedToggles] = useState<string[]>([]);
    React.useEffect(() => {
        const dividedWallets = {};
        productTypes.forEach(key => dividedWallets[key] = []);
        props.wallets.forEach(wallet => {
            const productType = wallet.productType;
            dividedWallets[productType].push(wallet);
        });
        setWallets(dividedWallets as WalletStore);
    }, [props.wallets]);

    if (Object.keys(wallets).length === 0) return null;
    return <AccordionWrapper>
        {productTypes.filter(key => wallets[key].length > 0).map((key: ProductType) => {
            const walletsList: Wallet[] = wallets[key];
            const asPercent = resultAsPercent(totalUsageFromMultipleWallets(walletsList));

            let earliestExpiration = VERY_HIGH_DATE_VALUE;
            walletsList.forEach(it => it.allocations.forEach(alloc => {
                if (alloc.endDate && alloc.endDate < earliestExpiration) earliestExpiration = alloc.endDate;
            }));

            const expirationText = earliestExpiration === VERY_HIGH_DATE_VALUE ?
                "" : `Earliest expiration: ${format(earliestExpiration, FORMAT)}`;

            return <Accordion
                icon={productTypeToIcon(key)}
                title={prettierString(key)}
                titleContent={<><Text color="text" mt="-4px" mr="16px">{expirationText}</Text><ResourceProgress value={Math.round(asPercent)} /></>}
            >
                <Border>
                    <Spacer left={null} right={<><Text mt="-4px" mr="12px">Advanced view</Text><Toggle checked={advancedToggles.includes(key)} onChange={() => {
                        if (advancedToggles.includes(key)) {
                            setAdvancedToggles([...advancedToggles.filter(it => it !== key)]);
                        } else {
                            setAdvancedToggles([...advancedToggles, key]);
                        }
                    }} /></>} />
                    <SimpleWalletView wallets={walletsList} advancedView={advancedToggles.includes(key)} />
                </Border>
            </Accordion>
        })}
    </AccordionWrapper>;
}

const Border = styled.div`
    border-top: 1px solid lightGrey;
    border-bottom: 1px solid lightGrey;
    padding: 12px;
`;

function SimpleWalletView(props: {wallets: Wallet[]; advancedView: boolean;}): JSX.Element {
    return <SimpleWalletRowWrapper>
        {props.wallets.map(wallet => {
            const asPercent = resultAsPercent(totalUsageFromWallet(wallet));
            const expiration = wallet.allocations.reduce((lowest, wallet) =>
                wallet.endDate && wallet.endDate < lowest ? wallet.endDate! : lowest, VERY_HIGH_DATE_VALUE
            );
            const expirationText = expiration === VERY_HIGH_DATE_VALUE ? "" : `Earliest expiration: ${format(expiration, FORMAT)}`;
            return (
                <SimpleAllocationRowWrapper>
                    <Spacer
                        px="30px"
                        left={<Text color="text" mt="-4px">{wallet.paysFor.name} @ {wallet.paysFor.provider}</Text>}
                        right={<><Text color="text" mt="-4px" mr="16px">{expirationText}</Text><ResourceProgress value={Math.round(asPercent)} /></>}
                    />
                    {props.advancedView ? <VisualizationSection><WalletViewer wallet={wallet} /></VisualizationSection> : null}
                </SimpleAllocationRowWrapper>
            );
        })}
    </SimpleWalletRowWrapper>;
}

const SimpleAllocationRowWrapper = styled.div``;
const SimpleWalletRowWrapper = styled.div`
    & > ${SimpleAllocationRowWrapper}:not(:last-child) {
        vertical-align: center;
        border-bottom: 1px solid #d3d3d3;
    }
    
    & > ${SimpleAllocationRowWrapper} {
        margin-top: 12px;
        padding-bottom: 10px;
        border-bottom: 0px solid black;
    }
`;

function totalUsageFromMultipleWallets(wallets: Wallet[]): {balance: number, initialBalance: number} {
    return wallets.reduce((acc, wallet) => {
        const usage = totalUsageFromWallet(wallet);
        acc.balance += usage.balance;
        acc.initialBalance += usage.initialBalance;
        return acc;
    }, {balance: 0, initialBalance: 0});

}

function totalUsageFromWallet(wallet: Wallet): {balance: number, initialBalance: number} {
    return wallet.allocations.reduce(
        (acc, it) => ({balance: acc.balance + it.balance, initialBalance: acc.initialBalance + it.initialBalance}),
        {balance: 0, initialBalance: 0}
    );
}

const WalletViewer: React.FunctionComponent<{wallet: Wallet}> = ({wallet}) => {
    return <>
        {wallet.allocations.map((it, idx) => <AllocationViewer key={idx} wallet={wallet} allocation={it} />)}
    </>
}

const AllocationViewer: React.FunctionComponent<{
    wallet: Wallet;
    allocation: WalletAllocation;
}> = ({wallet, allocation}) => {
    const url = "/project/grants/view/" + allocation.grantedIn;
    return <HighlightedCard color={"red"} width={"400px"}>
        <Flex flexDirection={"row"} alignItems={"center"} height={"100%"}>
            <Icon name={wallet.productType ? productTypeToIcon(wallet.productType) : "cubeSolid"}
                size={"54px"} mr={"16px"} />
            <Flex flexDirection={"column"} height={"100%"} width={"100%"}>
                <Flex alignItems={"center"} mr={"-16px"}>
                    <div><b>Allocation ID: {allocation.id}</b></div>
                    <Box flexGrow={1} />
                </Flex>
                <div>{usageExplainer(allocation.balance, wallet.productType, wallet.chargeType, wallet.unit)} remaining</div>
                <div>{usageExplainer(allocation.initialBalance, wallet.productType, wallet.chargeType, wallet.unit)} allocated</div>
                <Box flexGrow={1} mt={"8px"} />
                <div><ExpiresIn startDate={allocation.startDate} endDate={allocation.endDate} /></div>
                <div> {allocation.grantedIn != null ? <><Link to={url}> Show Grant </Link> </> : null}  </div>
            </Flex>
        </Flex>
    </HighlightedCard>;
};

const ExpiresIn: React.FunctionComponent<{startDate: number, endDate?: number | null;}> = ({startDate, endDate}) => {
    const now = timestampUnixMs();
    if (now < startDate) {
        return <>Starts in {formatDistance(new Date(startDate), new Date(now))}</>;
    } else if (endDate == null) {
        return <>No expiration</>;
    } else if (now < endDate) {
        return <>Expires in {formatDistance(new Date(endDate), new Date(now))}</>;
    } else {
        return <>Expires soon</>;
    }
};

const VisualizationSection = styled.div`
        display: grid;
        grid-gap: 16px;
        padding: 10px 0;
        grid-template-columns: repeat(auto-fill, 400px);
        `;

const UsageChartStyle = styled.div`
        .usage-chart {
            width: calc(100% + 32px) !important;
        margin: -16px;
    }
        `;

const UsageChartViewer: React.FunctionComponent<{
    c: UsageChart;
    maximized?: boolean;
    onMaximizeToggle: () => void;
}> = ({c, maximized, onMaximizeToggle}) => {
    const [flattenedLines, names] = useMemo(() => {
        const names: string[] = [];
        const work: Record<string, Record<string, any>> = {};
        for (const line of c.chart.lines) {
            names.push(line.name);
            for (const point of line.points) {
                const key = point.timestamp.toString();
                const entry: Record<string, any> = work[key] ?? {};
                entry["timestamp"] = point.timestamp;
                entry[line.name] = point.value;
                work[key] = entry;
            }
        }

        const result: Record<string, any>[] = [];
        Object.keys(work).map(it => parseInt(it)).sort().forEach(bucket => {
            result.push(work[bucket]);
        });

        for (let i = 0; i < result.length; i++) {
            const previousBucket = i > 0 ? result[i - 1] : null;
            const currentBucket = result[i];

            for (const name of names) {
                if (!currentBucket.hasOwnProperty(name)) {
                    currentBucket[name] = previousBucket?.[name] ?? 0;
                }
            }
        }
        return [result, names];
    }, [c.chart]);

    const formatter = useCallback((amount: number) => {
        return usageExplainer(amount, c.type, c.chargeType, c.unit);
    }, [c.type, c.chargeType, c.unit])

    return <HighlightedCard color={"blue"} width={maximized ? "100%" : "400px"}
        height={maximized ? "900px" : undefined}>
        <UsageChartStyle>
            <Flex alignItems={"center"}>
                <div>
                    <Text color="gray">{productTypeToTitle(c.type)}</Text>
                    <Text bold my="-6px"
                        fontSize="24px">{usageExplainer(c.periodUsage, c.type, c.chargeType, c.unit)} used</Text>
                </div>
                <Box flexGrow={1} />
                <Icon name={"fullscreen"} cursor={"pointer"} onClick={onMaximizeToggle} />
            </Flex>

            <ResponsiveContainer className={"usage-chart"} height={maximized ? 800 : 170}>
                <AreaChart
                    margin={{
                        left: 0,
                        top: 4,
                        right: 0,
                        bottom: -28
                    }}
                    data={flattenedLines}
                >
                    <XAxis dataKey={"timestamp"} />
                    <Tooltip labelFormatter={dateFormatter} formatter={formatter} />
                    {names.map((it, index) =>
                        <Area
                            key={it}
                            type={"linear"}
                            opacity={0.8}
                            dataKey={it}
                            strokeWidth={"2px"}
                            stroke={getCssVar(("dark" + capitalized(COLORS[index % COLORS.length]) as ThemeColor))}
                            fill={getCssVar(COLORS[index % COLORS.length])}
                        />
                    )}
                </AreaChart>
            </ResponsiveContainer>
        </UsageChartStyle>
    </HighlightedCard>
};

const COLORS: [ThemeColor, ThemeColor, ThemeColor, ThemeColor, ThemeColor] = ["green", "red", "blue", "orange", "yellow"];

interface BreakdownChart {
    type: ProductType;
    chargeType: ChargeType;
    unit: ProductPriceUnit;
    chart: {points: {name: string, value: number}[]}
}

function toPercentageString(value: number) {
    return `${Math.round(value * 10_000) / 100} %`;
}

const DonutChart: React.FunctionComponent<{chart: BreakdownChart}> = props => {
    const totalUsage = props.chart.chart.points.reduce((prev, current) => prev + current.value, 0);
    if (totalUsage === 0) return null;
    return (
        <HighlightedCard
            height="400px"
            width={"400px"}
            color="purple"
            title={productTypeToTitle(props.chart.type)}
            icon={productTypeToIcon(props.chart.type)}
        >
            <Text color="darkGray" fontSize={1}>Usage across different products</Text>

            <Flex justifyContent={"center"}>
                <PieChart width={215} height={215}>
                    <Pie
                        data={props.chart.chart.points}
                        fill="#8884d8"
                        dataKey="value"
                        innerRadius={55}
                    >
                        {props.chart.chart.points.map((_, index) => (
                            <Cell key={`cell-${index}`} fill={getCssVar(COLORS[index % COLORS.length])} />
                        ))}
                    </Pie>
                </PieChart>
            </Flex>

            <Flex pb="12px" style={{overflowX: "auto"}} justifyContent={"center"}>
                {props.chart.chart.points.map((it, index) =>
                    <Box mx="4px" width="auto" style={{whiteSpace: "nowrap"}} key={it.name}>
                        <ChartPointName name={it.name} />
                        <Text
                            textAlign="center"
                            color={getCssVar(COLORS[index % COLORS.length])}
                        >
                            {toPercentageString(it.value / totalUsage)}
                        </Text>
                    </Box>
                )}
            </Flex>
        </HighlightedCard>
    )
}

function ChartPointName({name}: {name: string}): JSX.Element {
    const [first, second, third] = name.split(" / ");
    return (
        <div>
            <Text textAlign="center" fontSize="14px">{first}</Text>
            <SubText>{second} / {third}</SubText>
        </div>
    );
}

const SubText = styled.div`
        color: var(--gray);
        text-decoration: none;
        font-size: 10px;
        `;

export default Resources;
