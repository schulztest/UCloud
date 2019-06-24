import * as React from "react";
import {Link, Box, Icon, ExternalLink} from "ui-components";
import {capitalized} from "UtilityFunctions";
import {NotConnectedToZenodo} from "Utilities/ZenodoPublishingUtilities";
import {updatePageTitle} from "Navigation/Redux/StatusActions";
import {fetchPublications, setZenodoLoading} from "./Redux/ZenodoActions";
import {connect} from "react-redux";
import {dateToString} from "Utilities/DateUtilities";
import {List} from "Pagination/List";
import {ZenodoHomeProps, ZenodoOperations, ZenodoHomeStateProps, Publication} from ".";
import {Dispatch} from "redux";
import {OutlineButton} from "ui-components";
import * as Heading from "ui-components/Heading";
import {MainContainer} from "MainContainer/MainContainer";
import Table, {TableHeaderCell, TableRow, TableCell, TableBody, TableHeader} from "ui-components/Table";
import ClickableDropdown from "ui-components/ClickableDropdown";
import {ReduxObject} from "DefaultObjects";
import {setRefreshFunction} from "Navigation/Redux/HeaderActions";
import {Spacer} from "ui-components/Spacer";
import {EntriesPerPageSelector} from "Pagination";

type Props = ZenodoHomeProps & ZenodoOperations
function ZenodoHome(props: Props) {

    React.useEffect(() => {
        const {updatePageTitle, fetchPublications} = props;
        updatePageTitle();
        fetchPublications(0, 25);
        props.setRefresh(() => refresh());
        return () => props.setRefresh();
    }, []);

    function refresh() {
        props.fetchPublications(props.page.pageNumber, props.page.itemsPerPage);
        props.setRefresh(() => refresh());
    }

    const {connected, loading, page} = props;
    if (!connected && !loading) {
        return (<MainContainer main={<NotConnectedToZenodo />} />);
    } else {
        return (
            <MainContainer
                header={<Spacer left={<Box><Heading.h2>Upload progress</Heading.h2>
                    <Heading.h5>Connected to Zenodo</Heading.h5></ Box>}
                    right={<EntriesPerPageSelector
                        onChange={itemsPerPage => fetchPublications(0, itemsPerPage)}
                        content="Publications per page"
                        entriesPerPage={page.itemsPerPage}
                    />}
                />}
                main={
                    <List
                        loading={loading}
                        customEmptyPage={<Heading.h6>No Zenodo publications found.</Heading.h6>}
                        pageRenderer={(page) => (
                            <Table>
                                <TableHeader>
                                    <TableRow>
                                        <TableHeaderCell textAlign="left">ID</TableHeaderCell>
                                        <TableHeaderCell textAlign="left">Name</TableHeaderCell>
                                        <TableHeaderCell textAlign="left">Status</TableHeaderCell>
                                        <TableHeaderCell textAlign="left">Last update</TableHeaderCell>
                                        <TableHeaderCell />
                                    </TableRow>
                                </TableHeader>
                                <TableBody>
                                    {page.items.map((it, i) => (<PublicationRow publication={it} key={i} />))}
                                </TableBody>
                            </Table>
                        )}
                        page={page}
                        onPageChanged={pageNumber => fetchPublications(pageNumber, page.itemsPerPage)}
                    />}
                sidebar={
                    <Link to="/zenodo/publish/">
                        <OutlineButton fullWidth color="blue">Create new upload</OutlineButton>
                    </Link>
                }
            />
        );
    }
}

const PublicationRow = ({publication}: {publication: Publication}) => {
    const actionButton = publication.zenodoAction ? (
        <ExternalLink href={publication.zenodoAction}>Finish at Zenodo</ExternalLink>
    ) : null;
    return (
        <TableRow>
            <TableCell>{publication.id}</TableCell>
            <TableCell>{publication.name}</TableCell>
            <TableCell>{capitalized(publication.status)}</TableCell>
            <TableCell>{dateToString(publication.modifiedAt)}</TableCell>
            <TableCell>
                <ClickableDropdown width="145px" trigger={<Icon name="ellipsis" />}>
                    <Box ml="-17px" mr="-17px" pl="17px">
                        <Link to={`/zenodo/info/${encodeURIComponent(publication.id)}`}>Show More</Link>
                    </Box>
                    <Box ml="-17px" mr="-17px" pl="17px">
                        {actionButton}
                    </Box>
                </ClickableDropdown>
            </TableCell>
        </TableRow>);
}

const mapDispatchToProps = (dispatch: Dispatch): ZenodoOperations => ({
    fetchPublications: async (pageNo, pageSize) => {
        dispatch(setZenodoLoading(true));
        dispatch(await fetchPublications(pageNo, pageSize))
    },
    updatePageTitle: () => dispatch(updatePageTitle("Zenodo Overview")),
    setRefresh: refresh => dispatch(setRefreshFunction(refresh))
});

const mapStateToProps = (state: ReduxObject): ZenodoHomeStateProps => state.zenodo;
export default connect(mapStateToProps, mapDispatchToProps)(ZenodoHome);
