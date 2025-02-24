import * as React from "react";
import {ResourceBrowse} from "@/Resource/Browse";
import {ResourceRouter} from "@/Resource/Router";
import SharesApi, {Share} from "@/UCloud/SharesApi";
import { NavigateFunction, useLocation} from "react-router";
import {buildQueryString, getQueryParam} from "@/Utilities/URIUtilities";
import {SharedByTabs} from "@/Files/SharesOutgoing";
import {useCallback, useMemo} from "react";
import * as Heading from "@/ui-components/Heading";
import {useAvatars} from "@/AvataaarLib/hook";
import {BrowseType} from "@/Resource/BrowseType";
import {snackbarStore} from "@/Snackbar/SnackbarStore";
import {Client} from "@/Authentication/HttpClientInstance";

export const ShareBrowse: React.FunctionComponent<{
    onSelect?: (selection: Share) => void;
    isSearch?: boolean;
    browseType?: BrowseType;
}> = props => {
    const browseType = props.browseType ?? BrowseType.MainContent;
    const location = useLocation();
    const filterIngoing = getQueryParam(location.search, "filterIngoing") !== "false";
    const filterRejected = getQueryParam(location.search, "filterRejected") !== "false";
    const filterOriginalPath = getQueryParam(location.search, "filterOriginalPath");
    const avatars = useAvatars();

    const additionalFilters: Record<string, string> = useMemo(() => {
        const result: Record<string, string> = {};
        result["filterIngoing"] = filterIngoing.toString()
        if (filterOriginalPath) {
            result["filterOriginalPath"] = filterOriginalPath;
        }
        if (filterRejected) {
            result["filterRejected"] = filterRejected.toString();
        }
        return result;
    }, [filterIngoing]);

    const onSharesLoaded = useCallback((items: Share[]) => {
        if (items.length === 0) return;
        avatars.updateCache(items.map(it => it.specification.sharedWith));
    }, []);

    const navigateToEntry = React.useCallback((navigate: NavigateFunction, share: Share): void => {
        if (browseType === BrowseType.MainContent) {
            if (share.status.state === "APPROVED" || share.specification.sharedWith !== Client.username) {
                navigate(buildQueryString("/files", {path: share.status.shareAvailableAt}));
            } else {
                snackbarStore.addFailure("Share must be accepted to access.", false);
            }
        } else {
            // Should we handle this differently for other browseTypes?
            navigate(buildQueryString("/files", {path: share.status.shareAvailableAt}));
        }
    }, []);

    return <ResourceBrowse
        api={SharesApi}
        disableSearch // HACK(Jonas): THIS IS TEMPORARY, UNTIL SEARCH WORKS FOR ALL SHARES 
        onSelect={props.onSelect}
        browseType={browseType}
        isSearch={props.isSearch}
        onResourcesLoaded={onSharesLoaded}
        additionalFilters={additionalFilters}
        navigateToChildren={navigateToEntry}
        header={<SharedByTabs sharedByMe={!filterIngoing} />}
        headerSize={55}
        emptyPage={
            <Heading.h3 textAlign={"center"}>
                No shares match your search/filter criteria.
                <br />
                <small>You can create a new share by clicking 'Share' on one of your directories.</small>
            </Heading.h3>
        }
    />;
};

const Router: React.FunctionComponent = () => {
    return <ResourceRouter api={SharesApi} Browser={ShareBrowse} />;
};

export default Router;
