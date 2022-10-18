import { emptyPage, emptyPageV2 } from "@/DefaultObjects";
import {MainContainer} from "@/MainContainer/MainContainer";
import React, * as React from "react";
import {useCallback, useEffect, useState} from "react";
import styled from "styled-components";
import {Box, Flex, Link} from "@/ui-components";
import Grid from "@/ui-components/Grid";
import * as Heading from "@/ui-components/Heading";
import {Spacer} from "@/ui-components/Spacer";
import {EllipsedText} from "@/ui-components/Text";
import theme from "@/ui-components/theme";
import {ApplicationCard, CardToolContainer, hashF, SmallCard, Tag} from "./Card";
import * as Pages from "./Pages";
import {SidebarPages, useSidebarPage} from "@/ui-components/Sidebar";
import {useTitle} from "@/Navigation/Redux/StatusActions";
import {useRefreshFunction} from "@/Navigation/Redux/HeaderActions";
import {useCloudAPI, useCloudCommand} from "@/Authentication/DataHook";
import * as UCloud from "@/UCloud";
import {compute} from "@/UCloud";
import ApplicationSummaryWithFavorite = compute.ApplicationSummaryWithFavorite;
import AppStoreOverview = compute.AppStoreOverview;
import AppStoreOverviewSectionType = compute.AppStoreOverviewSectionType;
import {AppToolLogo} from "@/Applications/AppToolLogo";
import {ReducedApiInterface, useResourceSearch} from "@/Resource/Search";
import {PageV2, provider} from "@/UCloud";
import IntegrationApi = provider.im;
import { inDevEnvironment, onDevSite } from "@/UtilityFunctions";
import Spinner from "@/LoadingIcon/LoadingIcon";

export const ApiLike: ReducedApiInterface = {
    routingNamespace: "applications",
    titlePlural: "Applications"
};

export const ShowAllTagItem: React.FunctionComponent<{tag?: string}> = props => (
    <Link to={props.tag ? Pages.browseByTag(props.tag) : Pages.browse()}>{props.children}</Link>
);

function favoriteStatusKey(app: ApplicationSummaryWithFavorite): string {
    return `${app.metadata.name}/${app.metadata.version}`;
}

type FavoriteStatus = Record<string, {override: boolean, app: ApplicationSummaryWithFavorite}>;

export const ApplicationsOverview2: React.FunctionComponent = () => {
    /*const defaultTools = [
        "BEDTools",
        "Cell Ranger",
        "HOMER",
        "Kallisto",
        "MACS2",
        "nf-core",
        "Salmon",
        "SAMtools",
        "Seqtk",
    ];*/

    const [tools, fetchTools] = useCloudAPI(
        UCloud.compute.tools.listAll({page: 0, itemsPerPage: 50}),
        emptyPage
    );

    const [sections, fetchOverview] = useCloudAPI<AppStoreOverview>(
        {noop: true},
        {items: []}
    );


    const [refreshId, setRefreshId] = useState<number>(0);


    useEffect(() => {
        console.log("Fetching overview");
        fetchOverview(UCloud.compute.apps.appStoreOverview());
    }, [refreshId]);

    const defaultTools = tools.data.items.map(it => it.description.title);

    const featuredTags = [
        "Engineering",
        "Data Analytics",
        "Social Science",
        "Applied Science",
        "Natural Science",
        "Development",
        "Virtual Machines",
        "Digital Humanities",
        "Health Science",
        "Bioinformatics"
    ];

    // TODO(Brian)
    const [providers, fetchProviders] = useCloudAPI<PageV2<provider.IntegrationBrowseResponseItem>>(
        {noop: true},
        emptyPageV2
    );


    useResourceSearch(ApiLike);

    useTitle("Applications");
    useSidebarPage(SidebarPages.AppStore);
    const refresh = () => {
        setRefreshId(refreshId + 1);
    };
    useRefreshFunction(refresh);

    const [loadingCommand, invokeCommand] = useCloudCommand();
    const [favoriteStatus, setFavoriteStatus] = useState<FavoriteStatus>({});

    useEffect(() => {
        fetchProviders(IntegrationApi.browse({}));
    }, []);

    const onFavorite = useCallback(async (app: ApplicationSummaryWithFavorite) => {
        if (!loadingCommand) {
            const key = favoriteStatusKey(app);
            const isFavorite = key in favoriteStatus ? favoriteStatus[key].override : app.favorite;
            const newFavorite = {...favoriteStatus};
            newFavorite[key] = {override: !isFavorite, app};
            setFavoriteStatus(newFavorite);

            try {
                await invokeCommand(UCloud.compute.apps.toggleFavorite({
                    appName: app.metadata.name,
                    appVersion: app.metadata.version
                }));
            } catch (e) {
                newFavorite[key] = {override: isFavorite, app};
                setFavoriteStatus(newFavorite);
            }
        }
    }, [loadingCommand, favoriteStatus]);

    const [favorites, fetchFavorites] = useCloudAPI<UCloud.Page<ApplicationSummaryWithFavorite>>(
        {noop: true},
        emptyPage,
    );

    useEffect(() => {
        fetchFavorites(UCloud.compute.apps.retrieveFavorites({itemsPerPage: 100, page: 0}));
    }, [refreshId]);

    const main = (
        <>
            {<TagGrid
                tag={SPECIAL_FAVORITE_TAG}
                items={favorites.data.items}
                tagBanList={[]}
                columns={7}
                rows={3}
                favoriteStatus={favoriteStatus}
                onFavorite={onFavorite}
                refreshId={refreshId}
            />}

            {sections.loading ? <Spinner /> :
                sections.data.items.map(section => 
                    section.type === AppStoreOverviewSectionType.TAG ?
                        <>
                            <TagGrid
                                key={section.name+section.type}
                                tag={section.name}
                                items={section.apps}
                                columns={7}
                                rows={1}
                                favoriteStatus={favoriteStatus}
                                onFavorite={onFavorite}
                                //tagBanList={defaultTools}
                                refreshId={refreshId}
                            />
                        </>
                    :
                        <>
                            <ToolGroup refreshId={refreshId} items={section.apps} key={section.name} tag={section.name} />
                        </>
            )}
        </>
    );
    return (<MainContainer main={main} />);
};

const ScrollBox = styled(Box)`
    overflow-x: auto;
`;

const ToolGroupWrapper = styled(Box)`
    width: 100%;
    padding-bottom: 10px;
    padding-left: 10px;
    padding-right: 10px;
    margin-top: 30px;
    background-color: var(--appCard, #f00);
    box-shadow: ${theme.shadows.sm};
    border-radius: 5px;
    background-image: url("data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMTAwJSIgaGVpZ2h0PSIxMDAlIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxkZWZzPjxwYXR0ZXJuIHZpZXdCb3g9IjAgMCBhdXRvIGF1dG8iIHg9IjAiIHk9IjAiIGlkPSJwMSIgd2lkdGg9IjU2IiBwYXR0ZXJuVHJhbnNmb3JtPSJyb3RhdGUoMTUpIHNjYWxlKDAuNSAwLjUpIiBoZWlnaHQ9IjEwMCIgcGF0dGVyblVuaXRzPSJ1c2VyU3BhY2VPblVzZSI+PHBhdGggZD0iTTI4IDY2TDAgNTBMMCAxNkwyOCAwTDU2IDE2TDU2IDUwTDI4IDY2TDI4IDEwMCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjYzlkM2RmNDQiIHN0cm9rZS13aWR0aD0iMS41Ij48L3BhdGg+PHBhdGggZD0iTTI4IDBMMjggMzRMMCA1MEwwIDg0TDI4IDEwMEw1NiA4NEw1NiA1MEwyOCAzNCIgZmlsbD0ibm9uZSIgc3Ryb2tlPSIjYzlkM2RmNDQiIHN0cm9rZS13aWR0aD0iNCI+PC9wYXRoPjwvcGF0dGVybj48L2RlZnM+PHJlY3QgZmlsbD0idXJsKCNwMSkiIHdpZHRoPSIxMDAlIiBoZWlnaHQ9IjEwMCUiPjwvcmVjdD48L3N2Zz4=");
`;

const ToolImageWrapper = styled(Box)`
    display: flex;
    width: 200px;
    justify-items: center;
    justify-content: center;
    align-items: center;
    margin-right: 10px;
`;

const ToolImage = styled.div`
    & > * {
      max-width: 200px;
      max-height: 190px;
      margin-left: auto;
      margin-right: auto;
      margin-top: auto;
      margin-bottom: auto;
      height: auto;
      width: 100%;
    }
`;

// NOTE(Dan): We don't allow new lines in tags normally. As a result, we can be pretty confident that no application
// will have this tag.
const SPECIAL_FAVORITE_TAG = "\n\nFavorites\n\n";

type TagGridBoxProps = {
    isFavorite: boolean;
}

const TagGridTopBox = styled.div<TagGridBoxProps>`
    border-top-left-radius: 10px;
    border-top-right-radius: 10px;
    background-color: var(${props => props.isFavorite ? "--appStoreFavBg" : "--lightGray"},#f00);
`;

const TagGridBottomBox = styled.div<TagGridBoxProps>`
    padding: 0px 10px 15px 10px;
    ${props => props.isFavorite ? null : "overflow-x: scroll;"}
    border-bottom-left-radius: 10px;
    border-bottom-right-radius: 10px;
    background-color: var(${props => props.isFavorite ? "--appStoreFavBg" : "--lightGray"},#f00);
`;


interface TagGridProps {
    tag: string;
    items: ApplicationSummaryWithFavorite[];
    tagBanList?: string[];
    columns: number;
    rows: number;
    favoriteStatus: FavoriteStatus;
    onFavorite: (app: ApplicationSummaryWithFavorite) => void;
    refreshId: number;
}

const TagGrid: React.FunctionComponent<TagGridProps> = (
    {tag, rows, items, tagBanList = [], favoriteStatus, onFavorite, refreshId}: TagGridProps
) => {
    console.log("taggrid " + tag);
    console.log(items);
    const showFavorites = tag == SPECIAL_FAVORITE_TAG;

    console.log(tagBanList);
    let filteredItems = items
        .filter(it => !it.tags.some(_tag => tagBanList.includes(_tag)))
        .filter(item => {
            const isFavorite = favoriteStatus[favoriteStatusKey(item)]?.override ?? item.favorite;
            return isFavorite === showFavorites;
        });

    console.log("filtered");
    console.log(filteredItems);

    if (showFavorites) {
        filteredItems = filteredItems.concat(
            Object.values(favoriteStatus)
                .filter(it => it.override)
                .map(it => it.app)
        );

        filteredItems = filteredItems.filter(it => favoriteStatus[favoriteStatusKey(it)]?.override !== false);
    }

    // Remove duplicates (This can happen due to favorite cache)
    {
        const observed = new Set<string>();
        const newList: ApplicationSummaryWithFavorite[] = [];
        for (const item of filteredItems) {
            const key = favoriteStatusKey(item);
            if (!observed.has(key)) {
                observed.add(key);
                newList.push(item);
            }
        }

        filteredItems = newList;
    }

    filteredItems = filteredItems.sort((a, b) => a.metadata.title.localeCompare(b.metadata.title));
    if (filteredItems.length === 0) return (null);

    return (
        <>
            <TagGridTopBox isFavorite={showFavorites}>
                <Spacer
                    mt="15px" px="10px" alignItems={"center"}
                    left={<Heading.h2>{showFavorites ? "Favorites" : tag}</Heading.h2>}
                    right={(
                        showFavorites ? null : (
                            <ShowAllTagItem tag={tag}>
                                <Heading.h4>Show All</Heading.h4>
                            </ShowAllTagItem>
                        )
                    )}
                />
            </TagGridTopBox>
            <TagGridBottomBox isFavorite={showFavorites}>
                <Grid
                    pt="20px"
                    gridGap="15px"
                    gridTemplateRows={showFavorites ? undefined : `repeat(${rows} , 1fr)`}
                    gridTemplateColumns={showFavorites ? "repeat(auto-fill, minmax(400px, 1fr))" : "repeat(auto-fill, 400px)"}
                    style={{gridAutoFlow: showFavorites ? "row" : "column"}}
                >
                    {filteredItems.map(app => (
                        <ApplicationCard
                            key={`${app.metadata.name}-${app.metadata.version}`}
                            onFavorite={() => onFavorite(app)}
                            app={app}
                            isFavorite={showFavorites}
                            tags={app.tags}
                        />
                    ))}
                </Grid>
            </TagGridBottomBox>
        </>
    );
};

const ToolGroup: React.FunctionComponent<{tag: string, items: ApplicationSummaryWithFavorite[], refreshId: number}> = ({tag, items, refreshId}) => {
    console.log("Toolgroup"+tag);
    console.log(items);
    /*const [appResp, fetchApplications] = useCloudAPI<UCloud.Page<ApplicationSummaryWithFavorite>>(
        {noop: true},
        emptyPage
    );

    useEffect(() => {
        fetchApplications(UCloud.compute.apps.searchTags({query: tag, itemsPerPage: 100, page: 0}));
    }, [tag, refreshId]);*/

    //const page = appResp.data;
    const allTags = items.map(it => it.tags);
    const tags = new Set<string>();
    allTags.forEach(list => list.forEach(tag => tags.add(tag)));

    return (
        <ToolGroupWrapper>
            <Spacer
                alignItems="center"
                left={<Heading.h3>{tag}</Heading.h3>}
                right={(
                    <ShowAllTagItem tag={tag}>
                        <Heading.h5 bold={false} regular={true}>Show All</Heading.h5>
                    </ShowAllTagItem>
                )}
            />
            <Flex>
                <ToolImageWrapper>
                    <ToolImage>
                        <AppToolLogo size="148px" name={tag.toLowerCase().replace(/\s+/g, "")} type={"TOOL"} />
                    </ToolImage>
                </ToolImageWrapper>
                <CardToolContainer>
                    <ScrollBox>
                        <Grid
                            py="10px"
                            pl="10px"
                            gridTemplateRows="repeat(2, 1fr)"
                            gridTemplateColumns="repeat(9, 1fr)"
                            gridGap="8px"
                            gridAutoFlow="column"
                        >
                            {items.map(application => {
                                const [first, second, third] = getColorFromName(application.metadata.name);
                                const withoutTag = removeTagFromTitle(tag, application.metadata.title);
                                console.log(tag);
                                console.log(application.metadata.title);
                                console.log(withoutTag);
                                return (
                                    <div key={application.metadata.name}>
                                        <SmallCard
                                            title={withoutTag}
                                            color1={first}
                                            color2={second}
                                            color3={third}
                                            to={Pages.runApplication(application.metadata)}
                                            color="white"
                                        >
                                            <EllipsedText>{withoutTag}</EllipsedText>
                                        </SmallCard>
                                    </div>
                                );
                            })}
                        </Grid>
                    </ScrollBox>
                    <Flex flexDirection="row" alignItems="flex-start">
                        {[...tags].filter(it => it !== tag).map(tag => (
                            <ShowAllTagItem tag={tag} key={tag}><Tag key={tag} label={tag} /></ShowAllTagItem>
                        ))}
                    </Flex>
                </CardToolContainer>
            </Flex>
        </ToolGroupWrapper>
    );
};

function removeTagFromTitle(tag: string, title: string): string {
    const titlenew = title.replace(/homerTools/g, "").replace(/seqtk: /i, "");
    if (titlenew !== title) return titlenew;
    if (title.startsWith(tag)) {
        if (titlenew.endsWith("pl")) {
            return titlenew.slice(tag.length + 2, -3);
        } else {
            //return titlenew.slice(tag.length + 2);
            return titlenew;
        }
    } else {
        return title;
    }
}

function getColorFromName(name: string): [string, string, string] {
    const hash = hashF(name);
    const num = (hash >>> 22) % (theme.appColors.length - 1);
    return theme.appColors[num] as [string, string, string];
}

export default ApplicationsOverview2;
